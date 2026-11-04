package com.joyos.launcher.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Monitors USB OTG TPMS receiver dongle attachment and tire pressure telemetry broadcasts
 * for all 4 wheels (FL, FR, RL, RR) on the Chevrolet Joy.
 */
class TpmsUsbReceiver(
    private val context: Context,
    private val onTpmsUpdate: (flPsi: Int, frPsi: Int, rlPsi: Int, rrPsi: Int, lowAlert: Boolean, connected: Boolean) -> Unit
) {
    private var isRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    onTpmsUpdate(32, 32, 32, 32, false, true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onTpmsUpdate(32, 32, 32, 32, false, false)
                }
                ACTION_TPMS_DATA -> {
                    val fl = intent.getIntExtra("psi_fl", 32)
                    val fr = intent.getIntExtra("psi_fr", 32)
                    val rl = intent.getIntExtra("psi_rl", 32)
                    val rr = intent.getIntExtra("psi_rr", 32)
                    val alert = fl < 26 || fr < 26 || rl < 26 || rr < 26
                    onTpmsUpdate(fl, fr, rl, rr, alert, true)
                }
            }
        }
    }

    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_TPMS_DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isRegistered = true
        checkInitialUsbDevices()
    }

    fun unregister() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        isRegistered = false
    }

    private fun checkInitialUsbDevices() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val devices: HashMap<String, UsbDevice> = usbManager.deviceList ?: return
        val hasTpmsDongle = devices.values.any { dev ->
            // Common USB Serial / TPMS vendor IDs (CH340: 0x1A86, CP210x: 0x10C4, FTDI: 0x0403)
            dev.vendorId == 0x1A86 || dev.vendorId == 0x10C4 || dev.vendorId == 0x0403
        }
        if (hasTpmsDongle) {
            onTpmsUpdate(32, 32, 32, 32, false, true)
        }
    }

    companion object {
        const val ACTION_TPMS_DATA = "com.joyos.launcher.TPMS_TELEMETRY"
    }
}
