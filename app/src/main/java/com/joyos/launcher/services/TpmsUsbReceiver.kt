package com.joyos.launcher.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class TpmsState(
    val flPsi: Int = -1,
    val frPsi: Int = -1,
    val rlPsi: Int = -1,
    val rrPsi: Int = -1,
    val flTempC: Int = -99,
    val frTempC: Int = -99,
    val rlTempC: Int = -99,
    val rrTempC: Int = -99,
    val lowAlert: Boolean = false,
    val connected: Boolean = false,
    val source: String = "disconnected",
    val deviceLabel: String = "",
    val lastRawHex: String = ""
)

/**
 * Plug & Play USB Serial + Broadcast Receiver for 433.92 MHz Android USB TPMS receivers
 * (e.g. Jansite / Careud / CH340 / CP210x without requiring any external third-party APK).
 */
class TpmsUsbReceiver(
    private val context: Context,
    private val onTpmsUpdated: (TpmsState) -> Unit
) {
    companion object {
        private const val TAG = "JoyTPMS"
        private const val ACTION_USB_PERMISSION = "com.joyos.launcher.USB_TPMS_PERMISSION"
        private const val ACTION_TPMS_DATA = "com.tpms.TPMS_DATA"
        private const val ACTION_JANSITE_DATA = "com.jansite.tpms.ACTION_DATA"
        private const val ACTION_CAREUD_DATA = "com.careud.tpms.DATA"
    }

    @Volatile
    var currentState: TpmsState = TpmsState()
        private set

    @Volatile
    var lowPsiThreshold: Int = 26

    @Volatile
    var highTempThresholdC: Int = 75

    private val usbManager: UsbManager? by lazy {
        context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    private var isRegistered = false
    private val serialRunning = AtomicBoolean(false)
    private var serialThread: Thread? = null
    private var activePort: UsbSerialPort? = null
    private val permissionRequestedDevices = mutableSetOf<String>()

    // Mutable wheel state accumulated from incoming frames
    private var flPsi = -1
    private var frPsi = -1
    private var rlPsi = -1
    private var rrPsi = -1
    private var flTemp = -99
    private var frTemp = -99
    private var rlTemp = -99
    private var rrTemp = -99
    private var flFault = false
    private var frFault = false
    private var rlFault = false
    private var rrFault = false
    private var seenZeroOrHex10Layout = false
    private var seenOneBasedLayout = false
    private var lastRawHex = ""

    private val tpmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return

            when (action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = getUsbDeviceExtra(intent)
                    Log.d(TAG, "USB permission result granted=$granted dev=${dev?.deviceName}")
                    if (granted) {
                        probeAndStartSerial()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    permissionRequestedDevices.clear()
                    probeAndStartSerial()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = getUsbDeviceExtra(intent)
                    if (dev != null && activePort?.device?.deviceName == dev.deviceName) {
                        stopSerialReader()
                        resetWheelData("disconnected", "")
                        emitState()
                    }
                }
                ACTION_TPMS_DATA, ACTION_JANSITE_DATA, ACTION_CAREUD_DATA -> {
                    try {
                        val fl = intent.getIntExtra("fl_psi", flPsi)
                        val fr = intent.getIntExtra("fr_psi", frPsi)
                        val rl = intent.getIntExtra("rl_psi", rlPsi)
                        val rr = intent.getIntExtra("rr_psi", rrPsi)
                        if (fl >= 0 || fr >= 0 || rl >= 0 || rr >= 0) {
                            flPsi = fl
                            frPsi = fr
                            rlPsi = rl
                            rrPsi = rr
                            currentState = buildState(connected = true, source = "broadcast", label = action)
                            onTpmsUpdated(currentState)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Broadcast TPMS parse warning: ${e.message}")
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    fun register() {
        if (!isRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(ACTION_USB_PERMISSION)
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(ACTION_TPMS_DATA)
                    addAction(ACTION_JANSITE_DATA)
                    addAction(ACTION_CAREUD_DATA)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(tpmsReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(tpmsReceiver, filter)
                }
                isRegistered = true
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register TPMS receiver: ${e.message}")
            }
        }
        probeAndStartSerial()
    }

    fun unregister() {
        stopSerialReader()
        if (isRegistered) {
            try {
                context.unregisterReceiver(tpmsReceiver)
            } catch (_: Throwable) {}
            isRegistered = false
        }
    }

    fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            permissionRequestedDevices.clear()
            probeAndStartSerial()
        }
    }

    fun triggerReprobe() {
        permissionRequestedDevices.clear()
        stopSerialReader()
        probeAndStartSerial()
    }

    fun updateThresholds(lowPsi: Int, highTempC: Int) {
        lowPsiThreshold = lowPsi.coerceIn(15, 40)
        highTempThresholdC = highTempC.coerceIn(55, 95)
        if (currentState.connected) {
            currentState = buildState(
                connected = currentState.connected,
                source = currentState.source,
                label = currentState.deviceLabel
            )
            onTpmsUpdated(currentState)
        }
    }

    private fun findSerialDrivers(manager: UsbManager): List<UsbSerialDriver> {
        val defaultDrivers = try {
            UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        } catch (_: Throwable) {
            emptyList()
        }
        if (defaultDrivers.isNotEmpty()) return defaultDrivers

        // Custom fallback ProbeTable for unbranded Chinese USB TPMS adapters
        val table = ProbeTable()
        for (dev in manager.deviceList.values) {
            when (dev.vendorId) {
                0x1A86 -> table.addProduct(dev.vendorId, dev.productId, Ch34xSerialDriver::class.java)
                0x10C4 -> table.addProduct(dev.vendorId, dev.productId, Cp21xxSerialDriver::class.java)
                0x067B -> table.addProduct(dev.vendorId, dev.productId, ProlificSerialDriver::class.java)
                0x0403 -> table.addProduct(dev.vendorId, dev.productId, FtdiSerialDriver::class.java)
            }
        }
        return try {
            UsbSerialProber(table).findAllDrivers(manager)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun probeAndStartSerial() {
        val manager = usbManager ?: return
        if (serialRunning.get()) return

        val drivers = findSerialDrivers(manager)
        if (drivers.isEmpty()) {
            Log.d(TAG, "No USB serial TPMS driver found (${manager.deviceList.size} USB devices attached)")
            return
        }

        // Prefer CH340/CP210x TPMS receiver if multiple serial devices exist
        val driver = drivers.firstOrNull {
            val pname = it.device.productName?.lowercase() ?: ""
            pname.contains("tpms") || it.device.vendorId == 0x1A86 || it.device.vendorId == 0x10C4
        } ?: drivers.first()

        val device = driver.device
        if (!manager.hasPermission(device)) {
            if (!permissionRequestedDevices.contains(device.deviceName)) {
                permissionRequestedDevices.add(device.deviceName)
                requestUsbPermission(manager, device)
            }
            return
        }

        startSerialReader(manager, driver)
    }

    private fun requestUsbPermission(manager: UsbManager, device: UsbDevice) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, 0, permIntent, flags)
            manager.requestPermission(device, pendingIntent)
            Log.d(TAG, "Requested USB permission for ${device.deviceName} (${device.vendorId}:${device.productId})")
        } catch (e: Throwable) {
            Log.w(TAG, "USB permission request error: ${e.message}")
        }
    }

    private fun startSerialReader(manager: UsbManager, driver: UsbSerialDriver) {
        val port = driver.ports.firstOrNull() ?: return
        val devLabel = "${driver.device.productName ?: "USB TPMS"} (${String.format(Locale.US, "%04X:%04X", driver.device.vendorId, driver.device.productId)})"

        serialRunning.set(true)
        serialThread = Thread {
            var connection = try {
                manager.openDevice(driver.device)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to open USB device: ${e.message}")
                null
            }
            if (connection == null) {
                serialRunning.set(false)
                return@Thread
            }

            try {
                port.open(connection)
                activePort = port
                var baudRate = 19200
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try { port.setDTR(true) } catch (_: Throwable) {}
                try { port.setRTS(true) } catch (_: Throwable) {}

                // Notify UI that USB receiver hardware is connected and awaiting 433.92 MHz tire frames
                currentState = buildState(connected = true, source = "usb-serial", label = devLabel)
                onTpmsUpdated(currentState)

                sendJansiteHandshake(port)

                val readBuf = ByteArray(128)
                val streamBuf = ByteArrayOutputStream()
                var bytesSinceValidFrame = 0
                var lastHandshakeMs = System.currentTimeMillis()

                while (serialRunning.get()) {
                    val len = try {
                        port.read(readBuf, 500)
                    } catch (e: Throwable) {
                        Log.w(TAG, "USB TPMS read ended: ${e.message}")
                        break
                    }

                    if (len > 0) {
                        streamBuf.write(readBuf, 0, len)
                        val bytes = streamBuf.toByteArray()
                        val consumed = processIncomingBuffer(bytes, devLabel)
                        if (consumed.matchedValidFrame) {
                            bytesSinceValidFrame = 0
                        } else {
                            bytesSinceValidFrame += len
                        }

                        streamBuf.reset()
                        if (consumed.remainingStart < bytes.size) {
                            streamBuf.write(bytes, consumed.remainingStart, bytes.size - consumed.remainingStart)
                        } else if (bytes.size > 256) {
                            // Trim un-synced noise
                            streamBuf.write(bytes, bytes.size - 32, 32)
                        }

                        // Auto-fallback from 19200 -> 9600 if only unsynced gibberish arrives
                        if (bytesSinceValidFrame > 48 && baudRate == 19200) {
                            baudRate = 9600
                            try {
                                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                                bytesSinceValidFrame = 0
                                sendJansiteHandshake(port)
                                Log.d(TAG, "Switched USB TPMS baud rate fallback to 9600")
                            } catch (_: Throwable) {}
                        }
                    } else {
                        // Gentle periodic poll every 8 seconds if connected with no frames yet
                        val now = System.currentTimeMillis()
                        if (now - lastHandshakeMs > 8000 && flPsi < 0 && frPsi < 0 && rlPsi < 0 && rrPsi < 0) {
                            lastHandshakeMs = now
                            sendJansiteHandshake(port)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "USB serial session error: ${e.message}")
            } finally {
                serialRunning.set(false)
                try { port.close() } catch (_: Throwable) {}
                try { connection.close() } catch (_: Throwable) {}
                activePort = null
                resetWheelData("disconnected", "")
                emitState()
            }
        }.apply {
            name = "JoyTpmsUsbThread"
            isDaemon = true
            start()
        }
    }

    private fun sendJansiteHandshake(port: UsbSerialPort) {
        try {
            // Query stored tire state & heartbeat on Jansite / Careud USB TPMS receivers
            val queryCmd = byteArrayOf(0x55, 0xAA.toByte(), 0x06, 0x07, 0x00, 0xFE.toByte())
            val initCmd = byteArrayOf(0x55, 0xAA.toByte(), 0x06, 0x01, 0x00, 0xF8.toByte())
            port.write(queryCmd, 250)
            Thread.sleep(60)
            port.write(initCmd, 250)
        } catch (_: Throwable) {}
    }

    private data class ParseResult(val remainingStart: Int, val matchedValidFrame: Boolean)

    private fun processIncomingBuffer(data: ByteArray, devLabel: String): ParseResult {
        var idx = 0
        var matched = false
        while (idx <= data.size - 6) {
            val b0 = data[idx].toInt() and 0xFF
            val b1 = data[idx + 1].toInt() and 0xFF
            if (b0 == 0x55 && b1 == 0xAA) {
                val frameLen = data[idx + 2].toInt() and 0xFF
                if (frameLen in 6..16) {
                    if (idx + frameLen > data.size) {
                        // Incomplete frame, wait for remaining bytes
                        return ParseResult(idx, matched)
                    }
                    val frame = data.copyOfRange(idx, idx + frameLen)
                    val hex = frame.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
                    lastRawHex = hex
                    Log.d(TAG, "TPMS Frame: $hex")
                    if (parseJansiteFrame(frame)) {
                        matched = true
                        currentState = buildState(connected = true, source = "usb-serial", label = devLabel)
                        onTpmsUpdated(currentState)
                    } else {
                        // Still store lastRawHex for REA diagnostics
                        currentState = currentState.copy(lastRawHex = lastRawHex, deviceLabel = devLabel, connected = true)
                    }
                    idx += frameLen
                    continue
                }
            }
            idx++
        }
        return ParseResult(idx, matched)
    }

    private fun parseJansiteFrame(frame: ByteArray): Boolean {
        if (frame.size < 8) return false
        val len = frame[2].toInt() and 0xFF
        if (len != 0x08) return false

        val wheelCode = frame[3].toInt() and 0xFF
        val pRaw = frame[4].toInt() and 0xFF
        val tRaw = frame[5].toInt() and 0xFF
        val flags = frame[6].toInt() and 0xFF

        // Disambiguate Jansite/Deelife hex tire IDs:
        // Variant A: 0x00=FL, 0x01=FR, 0x10=RL, 0x11=RR
        // Variant B: 0x01=FL, 0x02=FR, 0x03=RL, 0x04=RR
        when (wheelCode) {
            0x00, 0x10, 0x11 -> seenZeroOrHex10Layout = true
            0x02, 0x03, 0x04 -> seenOneBasedLayout = true
        }

        // Convert raw pressure to PSI: standard 0-8 Bar Android USB TPMS (~3.447 kPa/bit = 0.5 PSI/bit)
        val psi = if (pRaw in 0..240) (pRaw * 0.5).roundToInt() else -1
        val tempC = if (tRaw in 10..200) (tRaw - 50) else -99
        val sensorFault = (flags and 0x58) != 0 // fast/slow leak (0x08), low batt (0x10), lost (0x40)

        val slot = when (wheelCode) {
            0x00 -> 0 // FL
            0x01 -> if (seenOneBasedLayout && !seenZeroOrHex10Layout) 0 else 1
            0x02 -> 1 // FR
            0x10, 0x03 -> 2 // RL
            0x11, 0x04 -> 3 // RR
            else -> -1
        }
        if (slot !in 0..3) return false

        when (slot) {
            0 -> {
                if (psi >= 0) flPsi = psi
                if (tempC > -90) flTemp = tempC
                flFault = sensorFault
            }
            1 -> {
                if (psi >= 0) frPsi = psi
                if (tempC > -90) frTemp = tempC
                frFault = sensorFault
            }
            2 -> {
                if (psi >= 0) rlPsi = psi
                if (tempC > -90) rlTemp = tempC
                rlFault = sensorFault
            }
            3 -> {
                if (psi >= 0) rrPsi = psi
                if (tempC > -90) rrTemp = tempC
                rrFault = sensorFault
            }
        }
        return true
    }

    private fun isWheelAlert(psi: Int, tempC: Int, fault: Boolean): Boolean {
        if (fault) return true
        if (psi >= 0 && (psi < lowPsiThreshold || psi > 46)) return true
        if (tempC > highTempThresholdC) return true
        return false
    }

    private fun buildState(connected: Boolean, source: String, label: String): TpmsState {
        val alert = isWheelAlert(flPsi, flTemp, flFault) ||
            isWheelAlert(frPsi, frTemp, frFault) ||
            isWheelAlert(rlPsi, rlTemp, rlFault) ||
            isWheelAlert(rrPsi, rrTemp, rrFault)
        return TpmsState(
            flPsi = flPsi,
            frPsi = frPsi,
            rlPsi = rlPsi,
            rrPsi = rrPsi,
            flTempC = flTemp,
            frTempC = frTemp,
            rlTempC = rlTemp,
            rrTempC = rrTemp,
            lowAlert = alert,
            connected = connected,
            source = source,
            deviceLabel = label,
            lastRawHex = lastRawHex
        )
    }

    private fun resetWheelData(source: String, label: String) {
        flPsi = -1; frPsi = -1; rlPsi = -1; rrPsi = -1
        flTemp = -99; frTemp = -99; rlTemp = -99; rrTemp = -99
        flFault = false; frFault = false; rlFault = false; rrFault = false
        currentState = TpmsState(connected = false, source = source, deviceLabel = label, lastRawHex = lastRawHex)
    }

    private fun emitState() {
        onTpmsUpdated(currentState)
    }

    private fun stopSerialReader() {
        serialRunning.set(false)
        try { activePort?.close() } catch (_: Throwable) {}
        activePort = null
        try { serialThread?.interrupt() } catch (_: Throwable) {}
        serialThread = null
    }
}
