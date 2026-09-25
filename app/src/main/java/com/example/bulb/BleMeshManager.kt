package com.example.bulb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.DataReceivedCallback
import no.nordicsemi.android.ble.callback.DataSentCallback
import java.util.UUID

/** BLE Mesh GATT manager: proxy service 0x1828, provisioning service 0x1827.
 *  Ported from Nordic's example (BSD-3) for ble 2.6.1. */
class BleMeshManager(context: Context) : BleManager(context) {

    interface DataCallbacks {
        fun onDataReceived(device: BluetoothDevice, mtu: Int, pdu: ByteArray)
        fun onDataSent(device: BluetoothDevice, mtu: Int, pdu: ByteArray)
    }

    companion object {
        private val MESH_PROXY_UUID = UUID.fromString("00001828-0000-1000-8000-00805F9B34FB")
        private val MESH_PROXY_DATA_IN = UUID.fromString("00002ADD-0000-1000-8000-00805F9B34FB")
        private val MESH_PROXY_DATA_OUT = UUID.fromString("00002ADE-0000-1000-8000-00805F9B34FB")
        private val MESH_PROV_UUID = UUID.fromString("00001827-0000-1000-8000-00805F9B34FB")
        private val MESH_PROV_DATA_IN = UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB")
        private val MESH_PROV_DATA_OUT = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
        private const val MTU_MAX = 517
        private const val MTU_DEFAULT = 23
    }

    var dataCallbacks: DataCallbacks? = null

    private var proxyIn: BluetoothGattCharacteristic? = null
    private var proxyOut: BluetoothGattCharacteristic? = null
    private var provIn: BluetoothGattCharacteristic? = null
    private var provOut: BluetoothGattCharacteristic? = null
    private var proxyMode = false
    @Volatile private var deviceReady = false

    private inner class GattCallback : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            gatt.getService(MESH_PROXY_UUID)?.let { s ->
                val i = s.getCharacteristic(MESH_PROXY_DATA_IN)
                val o = s.getCharacteristic(MESH_PROXY_DATA_OUT)
                if (i != null && o != null && notifyProp(o) && wnrProp(i)) {
                    proxyIn = i; proxyOut = o; proxyMode = true
                    return true
                }
            }
            gatt.getService(MESH_PROV_UUID)?.let { s ->
                val i = s.getCharacteristic(MESH_PROV_DATA_IN)
                val o = s.getCharacteristic(MESH_PROV_DATA_OUT)
                if (i != null && o != null && notifyProp(o) && wnrProp(i)) {
                    provIn = i; provOut = o; proxyMode = false
                    return true
                }
            }
            return false
        }

        override fun initialize() {
            requestMtu(MTU_MAX).enqueue()
            val rx = if (proxyMode) proxyOut else provOut
            rx?.let {
                setNotificationCallback(it).with(DataReceivedCallback { device, data ->
                    dataCallbacks?.onDataReceived(device, mtu, data.value ?: return@DataReceivedCallback)
                })
                enableNotifications(it).enqueue()
            }
        }

        override fun onDeviceReady() {
            deviceReady = true
        }

        override fun onServicesInvalidated() {
            overrideMtu(MTU_DEFAULT)
            deviceReady = false
            proxyMode = false
            proxyIn = null; proxyOut = null; provIn = null; provOut = null
        }
    }

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    override fun shouldClearCacheWhenDisconnected(): Boolean = true

    @SuppressLint("MissingPermission")
    fun startConnect(device: BluetoothDevice) {
        connect(device).useAutoConnect(true).retry(2, 2000).enqueue()
    }

    @SuppressLint("MissingPermission")
    fun stop() { disconnect().enqueue() }

    fun sendPdu(pdu: ByteArray) {
        if (!deviceReady) return
        val tx = (if (proxyMode) proxyIn else provIn) ?: return
        writeCharacteristic(tx, pdu, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .with(DataSentCallback { device, data ->
                dataCallbacks?.onDataSent(device, mtu, data.value ?: return@DataSentCallback)
            })
            .enqueue()
    }

    fun maximumPacketSize(): Int = (mtu - 3).coerceAtLeast(20)
    fun meshReady(): Boolean = deviceReady

    private fun notifyProp(c: BluetoothGattCharacteristic) =
        c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    private fun wnrProp(c: BluetoothGattCharacteristic) =
        c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
}
