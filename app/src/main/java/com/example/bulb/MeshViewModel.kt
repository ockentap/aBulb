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
import no.nordicsemi.android.mesh.transport.ConfigAppKeyAdd
import no.nordicsemi.android.mesh.transport.ConfigModelAppBind
import no.nordicsemi.android.mesh.provisionerstates.UnprovisionedMeshNode
import no.nordicsemi.android.mesh.transport.ProvisionedMeshNode
import no.nordicsemi.android.mesh.transport.ConfigAppKeyStatus
import no.nordicsemi.android.mesh.transport.ConfigModelAppStatus

sealed class ConnState {
    object Idle : ConnState()
    object Scanning : ConnState()
    object Connecting : ConnState()
    object Ready : ConnState()
    object Pairing : ConnState()
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
    @Volatile private var pairing = false
    @Volatile private var lastSeenMac: String = ""
    private var tidCounter = 0
    private var scanCb: android.bluetooth.le.ScanCallback? = null
    private var scanMode1828 = true

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
            override fun sendProvisioningPdu(node: UnprovisionedMeshNode, pdu: ByteArray) {
                scope.launch(Dispatchers.Main) {
                    try { ble.sendPdu(pdu) } catch (e: Exception) { st("Prov send failed: ${e.message}") }
                }
            }
            override fun onMeshPduCreated(pdu: ByteArray) {
                scope.launch(Dispatchers.Main) {
                    try { ble.sendPdu(pdu) } catch (e: Exception) { st("Send failed: ${e.message}") }
                }
            }
            override fun getMtu(): Int = ble.maximumPacketSize()
        })

        // provisioning progress
        meshApi.setProvisioningStatusCallbacks(object : no.nordicsemi.android.mesh.MeshProvisioningStatusCallbacks {
            override fun onProvisioningStateChanged(node: UnprovisionedMeshNode, state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States, data: ByteArray?) {
                st("Pairing… ${state.name.lowercase().replace('_', ' ')}")
            }
            override fun onProvisioningFailed(node: UnprovisionedMeshNode, state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States, data: ByteArray?) {
                pairing = false
                st("Pairing failed at ${state.name}")
                cs(ConnState.Error("Pairing failed — power-cycle bulb and retry"))
                scope.launch(Dispatchers.Main) { try { ble.stop() } catch (_: Exception) {} }
            }
            override fun onProvisioningCompleted(node: ProvisionedMeshNode, state: no.nordicsemi.android.mesh.provisionerstates.ProvisioningState.States, data: ByteArray?) {
                val net = network ?: return
                val nk = net.getNetKey(MeshConfig.NET_KEY_INDEX) ?: return
                val ak = net.getAppKey(MeshConfig.APP_KEY_INDEX) ?: return
                lastProvisioned = node
                st("Provisioned as 0x%04x — adding app key…".format(node.unicastAddress))
                // remember mac from the live gatt connection
                scope.launch(meshThread) {
                    try { meshApi.createMeshPdu(node.unicastAddress, ConfigAppKeyAdd(nk, ak)) }
                    catch (e: Exception) { st("AppKeyAdd failed: ${e.message}") }
                }
            }
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
                if (pairing) {
                    if (message is ConfigAppKeyStatus && message.isSuccessful) {
                        val net = network ?: return
                        val ak = net.getAppKey(MeshConfig.APP_KEY_INDEX) ?: return
                        st("App key added — binding light model…")
                        scope.launch(meshThread) {
                            try { meshApi.createMeshPdu(src, ConfigModelAppBind(src, MeshConfig.LIGHT_LIGHTNESS_MODEL_ID, ak.keyIndex)) }
                            catch (e: Exception) { st("Bind failed: ${e.message}") }
                        }
                    } else if (message is ConfigModelAppStatus && message.isSuccessful) {
                        val pn = (network?.getNode(src) as? ProvisionedMeshNode)
                        if (pn != null) finishPairing(pn) else st("Paired node missing")
                    }
                    return
                }
                if (src == (keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST) && message is LightLightnessStatus) {
                    br(message.presentLightness)
                    st("Bulb level: ${message.presentLightness}")
                    if (rampJob?.isActive != true) lastSent = message.presentLightness
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
            override fun onDeviceConnecting(device: BluetoothDevice) { lastSeenMac = device.address; if (!pairing) cs(ConnState.Connecting) }
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice) { if (!pairing) cs(ConnState.Idle) }
            override fun onLinkLossOccurred(device: BluetoothDevice) { if (!pairing) cs(ConnState.Idle) }
            override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {}
            override fun onDeviceReady(device: BluetoothDevice) {
                stopScan()
                if (pairing) beginHandshake()
                else { cs(ConnState.Ready); refreshState() }
            }
            override fun onBondingRequired(device: BluetoothDevice) {}
            override fun onBonded(device: BluetoothDevice) {}
            override fun onBondingFailed(device: BluetoothDevice) {}
            override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
                cs(ConnState.Error(message))
            }
            override fun onDeviceNotSupported(device: BluetoothDevice) {
                cs(ConnState.Error("Device has no mesh service"))
            }
        })

        ensureImported()
    }

    // ---------- pairing ----------

    @SuppressLint("MissingPermission")
    fun startPairing() {
        val ctx = getApplication<Application>()
        if (!hasPermissions(ctx)) { st("Grant Bluetooth permission first"); return }
        val adapter = adapter() ?: run { cs(ConnState.Error("No Bluetooth")); return }
        if (!adapter.isEnabled) { cs(ConnState.Error("Turn Bluetooth on")); return }
        disconnect()
        pairing = true; imported = false
        cs(ConnState.Pairing)
        st("Creating a fresh mesh network…")
        scope.launch(meshThread) {
            try {
                meshApi.createMeshNetwork()   // callback onNetworkLoaded sets network
                scope.launch(Dispatchers.Main) {
                    try {
                        val net = network!!
                        try { net.selectedProvisioner.assignProvisionerAddress(null) } catch (_: Exception) {}
                    } catch (_: Exception) {}
                    startPairScan(adapter)
                }
            } catch (e: Exception) { pairing = false; cs(ConnState.Error("Network init failed: ${e.message}")) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPairScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: run { st("Scanner unavailable"); return }
        st("Hold the bulb close. Power-cycle it to start advertising…")
        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(java.util.UUID.fromString("00001827-0000-1000-8000-00805F9B34FB")))
            .build()
        val cbs = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val d = result.device ?: return
                val adv = result.scanRecord ?: return
                // unprovisioned service data keyed by 0x1827 carries UUID[0:6] + random[6:8]... 
                val data = adv.getServiceData(android.os.ParcelUuid(java.util.UUID.fromString("00001827-0000-1000-8000-00805F9B34FB"))) ?: return
                if (data.size < 8) return
                val msb = data[0].toLong() and 0xFF shl 24 or (data[1].toLong() and 0xFF shl 16) or
                        (data[2].toLong() and 0xFF shl 8) or (data[3].toLong() and 0xFF)
                val lsb = data[4].toLong() and 0xFF shl 24 or (data[5].toLong() and 0xFF shl 16) or
                        (data[6].toLong() and 0xFF shl 8) or (data[7].toLong() and 0xFF)
                val uuid = java.util.UUID(msb, lsb)
                st("Found bulb — provisioning…")
                pendingUuid = uuid
                stopScan()
                scope.launch(Dispatchers.Main) {
                    try { ble.startConnect(d) } catch (e: Exception) { st("Prov connect failed: ${e.message}") }
                }
            }
        }
        scanCb = cbs
        try {
            scanner.startScan(listOf(filter),
                android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cbs)
        } catch (e: SecurityException) { cs(ConnState.Error("Bluetooth permission missing")); return }
        scope.launch {
            delay(45_000)
            if (pairing && connState.value is ConnState.Pairing) {
                stopScan()
                pairing = false
                cs(ConnState.Error("No unprovisioned bulb found. Power-cycle it and retry."))
            }
        }
    }

    @Volatile private var pendingUuid: java.util.UUID? = null
    @Volatile private var lastProvisioned: ProvisionedMeshNode? = null

    private fun beginHandshake() {
        val u = pendingUuid ?: run { st("Pairing lost uuid"); return }
        scope.launch(meshThread) {
            try {
                meshApi.startProvisioning(UnprovisionedMeshNode(u))
            } catch (e: Exception) { st("StartProvisioning failed: ${e.message}") }
        }
    }

    private fun finishPairing(pn: ProvisionedMeshNode) {
        pairing = false
        val net = network ?: return
        val unicast = pn.unicastAddress
        try {
            val nk = net.getNetKey(MeshConfig.NET_KEY_INDEX) ?: return
            val ak = net.getAppKey(MeshConfig.APP_KEY_INDEX) ?: return
            val devKey = pn.deviceKey ?: ByteArray(0)
            if (devKey.isEmpty()) { st("Provisioned, but device key unavailable"); return }
            val k = MeshKeys(
                net = bytesHex(nk.key),
                app = bytesHex(ak.key),
                dev = bytesHex(devKey),
                mac = lastSeenMac,
                bulbUnicast = unicast
            )
            KeyStore.save(getApplication(), k)
            keys = k
            // export the just-created network so future imports see identical state
            val json = meshApi.exportMeshNetwork()
            java.io.File(getApplication<Application>().filesDir, "abulb-network.json")
                .writeText(json.toString())
            java.io.File(getApplication<Application>().filesDir, "abulb-keys.json")
                .writeText(KeyStore.exportedJson(k))
            imported = true
            st("Bulb paired! Keys saved — share abulb-keys.json from Export button.")
            cs(ConnState.Ready)
            refreshState()
        } catch (e: Exception) { st("Finish pairing failed: ${e.message}") }
    }

    /** JSON (CDB) of the owned network for sharing with other aBulb installs. */
    fun exportNetworkJson(): String = try {
        java.io.File(getApplication<Application>().filesDir, "abulb-network.json").takeIf { it.exists() }?.readText()
            ?: meshApi.exportMeshNetwork().toString()
    } catch (e: Exception) { "" }

    fun exportedKeysJson(): String = keys?.let { KeyStore.exportedJson(it) } ?: ""

    // ---------- connect (proxy) ----------

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

    // ---------- brightness ----------

    private fun appKey(): ApplicationKey? =
        network?.appKeys?.firstOrNull { it.keyIndex == MeshConfig.APP_KEY_INDEX }

    fun setBrightness(level: Int) {
        rampJob?.cancel()
        sendLevel(level)
    }

    /** Live drag: throttled to one send per ~90ms so the light tracks the finger. */
    fun liveSet(level: Int) {
        rampJob?.cancel()
        val now = android.os.SystemClock.elapsedRealtime()
        if (level == lastSent || now - lastSendAt < 90) return
        sendLevel(level)
    }

    /** Preset tap: ramp through intermediate values, duration proportional to distance. */
    fun rampTo(target: Int) {
        rampJob?.cancel()
        rampJob = scope.launch(meshThread) {
            val from = if (lastSent >= 0) lastSent else target
            val dist = kotlin.math.abs(target - from)
            if (dist <= 2) { sendLevel(target); return@launch }
            val steps = minOf(dist, 24)
            for (i in 1..steps) {
                sendLevel(from + Math.round((target - from) * i.toFloat() / steps))
                delay(60)
            }
        }
    }

    private fun sendLevel(level: Int) {
        val key = appKey() ?: run { st("Network not imported yet"); return }
        if (connState.value !is ConnState.Ready) { st("Connect to the bulb first"); return }
        val clamped = level.coerceIn(0, MeshConfig.LIGHTNESS_MAX)
        scope.launch(meshThread) {
            try {
                meshApi.createMeshPdu(keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST, LightLightnessSet(key, clamped, nextTid()))
                lastSent = clamped; lastSendAt = android.os.SystemClock.elapsedRealtime()
            } catch (e: Exception) { st("Create failed: ${e.message}") }
        }
    }

    fun lastSentLevel(): Int = lastSent
    private var rampJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastSent = -1
    @Volatile private var lastSendAt = 0L

    fun refreshState() {
        val key = appKey() ?: return
        if (connState.value !is ConnState.Ready) return
        scope.launch(meshThread) {
            try { meshApi.createMeshPdu(keys?.bulbUnicast ?: MeshConfig.BULB_UNICAST, LightLightnessGet(key)) }
            catch (e: Exception) { st("Get failed: ${e.message}") }
        }
    }

    private fun bytesHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun nextTid(): Int = (tidCounter++ and 0x7F)

    fun ensureImported() {
        if (imported) return
        val k = KeyStore.load(getApplication()) ?: run {
            st("Add your mesh keys (Keys button) or pair a new bulb")
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
