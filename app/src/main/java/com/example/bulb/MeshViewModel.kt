package com.example.bulb

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import no.nordicsemi.android.mesh.MeshManagerApi
import no.nordicsemi.android.mesh.MeshManagerCallbacks
import no.nordicsemi.android.mesh.MeshNetwork
import no.nordicsemi.android.mesh.MeshStatusCallbacks
import no.nordicsemi.android.mesh.ApplicationKey
import no.nordicsemi.android.mesh.transport.LightLightnessSet
import no.nordicsemi.android.mesh.transport.LightLightnessGet
import no.nordicsemi.android.mesh.transport.LightLightnessStatus
import no.nordicsemi.android.mesh.transport.MeshMessage

sealed class ConnState {
    object Idle : ConnState()
    object Scanning : ConnState()
    object Connecting : ConnState()
    object Ready : ConnState()
    data class Error(val msg: String) : ConnState()
}

class MeshViewModel(app: Application) : androidx.lifecycle.AndroidViewModel(app) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val meshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val meshThread = meshExecutor.asCoroutineDispatcher()
    private val ble: BleMeshManager = BleMeshManager(app)
    private val meshApi: MeshManagerApi = MeshManagerApi(app)

    @Volatile private var network: MeshNetwork? = null
    @Volatile var keys: MeshKeys? = null
        private set
    @Volatile private var imported = false
    private var tidCounter = 0
    private var scanCb: android.bluetooth.le.ScanCallback? = null

    val connState = kotlinx.coroutines.flow.MutableStateFlow<ConnState>(ConnState.Idle)
    val brightness = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null) // raw 0..50
    val statusText = kotlinx.coroutines.flow.MutableStateFlow<String>("")

    private fun st(v: String) { scope.launch(Dispatchers.Main.immediate) { statusText.value = v } }
    private fun cs(v: ConnState) { scope.launch(Dispatchers.Main.immediate) { connState.value = v } }
    private fun br(v: Int) { scope.launch(Dispatchers.Main.immediate) { brightness.value = v } }

    init {
        meshApi.setMeshManagerCallbacks(object : MeshManagerCallbacks {
            override fun onNetworkLoaded(n: MeshNetwork) { network = n }
            override fun onNetworkUpdated(n: MeshNetwork) { network = n }
            override fun onNetworkLoadFailed(e: String) { st("Load failed: $e") }
            override fun onNetworkImported(n: MeshNetwork) {
                // CDB import leaves the provisioner address unset; assign the phone's unicast
                try {
                    if (n.selectedProvisioner.provisionerAddress == null) {
                        n.selectedProvisioner.assignProvisionerAddress(MeshConfig.PHONE_UNICAST)
                    }
                } catch (e: Exception) { st("Addr assign failed: ${e.message}") }
                network = n; imported = true
            }
            override fun onNetworkImportFailed(e: String) {
                st("Import failed: $e")
                cs(ConnState.Error("Key import failed"))
            }
            override fun sendProvisioningPdu(node: no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode, pdu: ByteArray) {}
            override fun onMeshPduCreated(pdu: ByteArray) {
                scope.launch(Dispatchers.Main) {
                    try { ble.sendPdu(pdu) } catch (e: Exception) { st("Send failed: ${e.message}") }
                }
            }
            override fun getMtu(): Int = ble.maximumPacketSize()
        })
        meshApi.setMeshStatusCallbacks(object : MeshStatusCallbacks {
            override fun onTransactionFailed(dst: Int, hasIncompleteTimerExpired: Boolean) {
                st("No response from bulb (timeout)")
            }
            override fun onUnknownPduReceived(src: Int, accessPayload: ByteArray) {}
            override fun onBlockAcknowledgementProcessed(dst: Int, message: no.nordicsemi.android.mesh.transport.ControlMessage) {}
            override fun onBlockAcknowledgementReceived(src: Int, message: no.nordicsemi.android.mesh.transport.ControlMessage) {}
            override fun onHeartbeatMessageReceived(src: Int, message: no.nordicsemi.android.mesh.transport.ControlMessage) {}
            override fun onMeshMessageProcessed(dst: Int, message: MeshMessage) { st("") }
            override fun onMeshMessageReceived(src: Int, message: MeshMessage) {
                if (src == (keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST) && message is LightLightnessStatus) {
                    br(message.presentLightness)
                    st("Bulb level: ${message.presentLightness}")
                }
            }
            override fun onMessageDecryptionFailed(meshLayer: String, errorMessage: String) {
                st("Decryption failed ($meshLayer)")
            }
        })

        ble.dataCallbacks = object : BleMeshManager.DataCallbacks {
            override fun onDataReceived(device: BluetoothDevice, mtu: Int, pdu: ByteArray) {
                scope.launch(meshThread) { meshApi.handleNotifications(mtu, pdu) }
            }
            override fun onDataSent(device: BluetoothDevice, mtu: Int, pdu: ByteArray) {
                scope.launch(meshThread) { meshApi.handleWriteCallbacks(mtu, pdu) }
            }
        }
        ble.setGattCallbacks(object : no.nordicsemi.android.ble.BleManagerCallbacks {
            override fun onDeviceConnecting(device: BluetoothDevice) { cs(ConnState.Connecting) }
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice) { cs(ConnState.Idle) }
            override fun onLinkLossOccurred(device: BluetoothDevice) { cs(ConnState.Idle) }
            override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {}
            override fun onDeviceReady(device: BluetoothDevice) {
                stopScan()
                cs(ConnState.Ready)
                refreshState()
            }
            override fun onBondingRequired(device: BluetoothDevice) {}
            override fun onBonded(device: BluetoothDevice) {}
            override fun onBondingFailed(device: BluetoothDevice) {}
            override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
                cs(ConnState.Error(message))
            }
            override fun onDeviceNotSupported(device: BluetoothDevice) {
                cs(ConnState.Error("Device has no mesh proxy service"))
            }
        })

        ensureImported()
    }

    private fun stopScan() {
        val cbs = scanCb ?: return
        scanCb = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cbs) } catch (_: Exception) {}
    }

    fun hasPermissions(ctx: Context): Boolean {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    private fun adapter(): BluetoothAdapter? =
        (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun connectBulb() {
        ensureImported()
        val ctx = getApplication<Application>()
        if (!hasPermissions(ctx)) { st("Grant Bluetooth permission first"); return }
        val adapter = adapter() ?: run {
            cs(ConnState.Error("No Bluetooth on this device")); return }
        if (!adapter.isEnabled) { cs(ConnState.Error("Turn Bluetooth on")); return }
        val bonded = try {
            adapter.bondedDevices.firstOrNull { it.address.equals(keys?.mac, true) }
        } catch (e: SecurityException) { null }
        if (bonded != null) {
            scope.launch(Dispatchers.Main) {
                try { ble.startConnect(bonded) } catch (e: Exception) { st("Connect failed: ${e.message}") }
            }
            return
        }
        startScan(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { cs(ConnState.Error("Scanner unavailable")); return }
        cs(ConnState.Scanning)
        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(java.util.UUID.fromString("00001828-0000-1000-8000-00805F9B34FB")))
            .build()
        val cbs = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val d = result.device ?: return
                val wantMac = keys?.mac?.takeIf { it.isNotBlank() }
                if (wantMac != null && !d.address.equals(wantMac, true)) return
                stopScan()
                scope.launch(Dispatchers.Main) {
                    try { ble.startConnect(d) } catch (e: Exception) { st("Connect failed: ${e.message}") }
                }
            }
        }
        scanCb = cbs
        try {
            scanner.startScan(listOf(filter),
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cbs)
        } catch (e: SecurityException) {
            cs(ConnState.Error("Bluetooth permission missing")); return
        }
        scope.launch {
            delay(12_000)
            if (connState.value is ConnState.Scanning) {
                stopScan()
                cs(ConnState.Error("Proxy not found — power-cycle the bulb"))
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.Main) {
            try { ble.stop() } catch (_: Exception) {}
        }
    }

    private fun appKey(): ApplicationKey? =
        network?.appKeys?.firstOrNull { it.keyIndex == MeshConfig.APP_KEY_INDEX }

    fun setBrightness(level: Int) {
        val key = appKey() ?: run { st("Network not imported yet"); return }
        if (connState.value !is ConnState.Ready) { st("Connect to the bulb first"); return }
        val clamped = level.coerceIn(0, MeshConfig.LIGHTNESS_MAX)
        scope.launch(meshThread) {
            try { meshApi.createMeshPdu(keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST, LightLightnessSet(key, clamped, nextTid())) }
            catch (e: Exception) { st("Create failed: ${e.message}") }
        }
    }

    fun refreshState() {
        val key = appKey() ?: return
        if (connState.value !is ConnState.Ready) return
        scope.launch(meshThread) {
            try { meshApi.createMeshPdu(keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST, LightLightnessGet(key)) }
            catch (e: Exception) { st("Get failed: ${e.message}") }
        }
    }

    private fun nextTid(): Int = (tidCounter++ and 0x7F)

    fun ensureImported() {
        if (imported) return
        val k = KeyStore.load(getApplication()) ?: run {
            st("Add your mesh keys (Keys button) — see README for the Pi export")
            return
        }
        keys = k
        scope.launch(meshThread) {
            try { meshApi.importMeshNetworkJson(CdbBuilder.build(k)) }
            catch (e: Exception) { st("Import crashed: ${e.message}") }
        }
    }

    override fun onCleared() {
        stopScan()
        scope.cancel()
        try { ble.close() } catch (_: Exception) {}
        meshExecutor.shutdown()
        super.onCleared()
    }
}
