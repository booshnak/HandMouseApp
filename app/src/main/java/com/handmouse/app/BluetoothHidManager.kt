package com.handmouse.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager(
    private val context: Context,
    private val onConnectionStateChanged: (String, Boolean) -> Unit
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastButtons: Byte = 0

    private val mouseReportDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x02.toByte(),
        0xA1.toByte(), 0x01.toByte(), 0x85.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x01.toByte(), 0xA1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(), 0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(), 0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(), 0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x01.toByte(), 0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(), 0x09.toByte(), 0x31.toByte(),
        0x09.toByte(), 0x38.toByte(), 0x15.toByte(), 0x81.toByte(),
        0x25.toByte(), 0x7F.toByte(), 0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x03.toByte(), 0x81.toByte(), 0x06.toByte(),
        0xC0.toByte(), 0xC0.toByte()
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered && pluggedDevice != null) {
                hidDevice?.connect(pluggedDevice)
            } else if (!registered) {
                connectedHost = null
                onConnectionStateChanged("HID Unregistered", false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    onConnectionStateChanged("Connected: ${device?.name ?: "PC"}", true)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    onConnectionStateChanged("Connecting: ${device?.name ?: "PC"}...", false)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost == device) connectedHost = null
                    onConnectionStateChanged("Disconnected", false)
                }
            }
        }
    }

    fun start() {
        val btAdapter = adapter ?: run {
            onConnectionStateChanged("Bluetooth unavailable", false)
            return
        }
        btAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "HandMouse", "Camera Hand Air Mouse", "Android",
                        BluetoothHidDevice.SUBCLASS1_MOUSE, mouseReportDescriptor
                    )
                    hidDevice?.registerApp(sdp, null, null, executor, callback)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    connectedHost = null
                    onConnectionStateChanged("HID Service Disconnected", false)
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun getBondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()
    fun connect(device: BluetoothDevice) { hidDevice?.connect(device) }
    fun disconnect() { connectedHost?.let { hidDevice?.disconnect(it) } }

    fun sendMouseReport(leftDown: Boolean, rightDown: Boolean, dx: Int, dy: Int, wheel: Int) {
        val host = connectedHost ?: return
        val device = hidDevice ?: return
        var buttons = 0
        if (leftDown) buttons = buttons or 0x01
        if (rightDown) buttons = buttons or 0x02
        val buttonByte = buttons.toByte()
        if (dx == 0 && dy == 0 && wheel == 0 && buttonByte == lastButtons) return
        lastButtons = buttonByte
        val report = byteArrayOf(
            buttonByte,
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
        device.sendReport(host, 1, report)
    }

    fun release() {
        try {
            hidDevice?.unregisterApp()
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {}
    }
}
