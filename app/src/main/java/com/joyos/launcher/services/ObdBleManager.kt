package com.joyos.launcher.services

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ObdState(
    val rpm: Int = 0,
    val coolantTempC: Int = -99,
    val speedKmh: Int = 0,
    val batteryVoltage: Double = -1.0,
    val engineLoadPct: Int = -1,
    val consumptionKmL: Double = -1.0,
    val connected: Boolean = false,
    val transport: String = "disconnected",
    val deviceName: String = "",
    val lastError: String = ""
)

/**
 * Dual-mode OBD2 telemetry manager engineered for Vgate iCar Pro BT 4.0 (ELM327 V2.3):
 * 1. Tries Classic BT SPP (`ANDROID-VLINK`, `Vgate`, `OBDII`, `ELM327`, RFCOMM 00001101)
 * 2. Automatically falls back to BLE GATT (`IOS-VLINK`, service 000018F0 / FFE0 / FFF0)
 *    which solves pairing issues on AC8227L head units.
 *
 * Never emits synthetic/hardcoded coolant (88°C) or voltage (14.1V) when disconnected.
 */
class ObdBleManager(
    private val context: Context,
    private val onTelemetry: (ObdState) -> Unit
) {
    companion object {
        private const val TAG = "JoyOBD"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Vgate iCar Pro BLE 4.0 GATT UUIDs (Primary) + Generic FFE0/FFF0 (Fallback)
        private val VGATE_SVC_UUID: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        private val VGATE_NOTIFY_UUID: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        private val VGATE_WRITE_UUID: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
        private val FFE0_SVC_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val FFE1_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val FFF0_SVC_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val FFF1_NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val FFF2_WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile
    var currentState: ObdState = ObdState()
        private set

    private var connectionJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private var parentScope: CoroutineScope? = null

    private var sppSocket: BluetoothSocket? = null
    private var activeGatt: BluetoothGatt? = null
    private var gattWriteChar: BluetoothGattCharacteristic? = null
    private val gattReady = AtomicBoolean(false)

    // BLE response assembly
    private val gattBuffer = StringBuilder()
    @Volatile
    private var pendingGattCmd: CompletableDeferred<String>? = null

    private fun getAdapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun matchesObdDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val u = name.uppercase(Locale.US)
        return u.contains("VLINK") || u.contains("V-LINK") ||
            u.contains("VGATE") || u.contains("ICAR") ||
            u.contains("OBD") || u.contains("ELM")
    }

    fun start(scope: CoroutineScope) {
        parentScope = scope
        if (isRunning.getAndSet(true)) return

        connectionJob = scope.launch(Dispatchers.IO) {
            emitState(ObdState(connected = false, transport = "scanning"))
            while (isActive && isRunning.get()) {
                val adapter = getAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    emitState(ObdState(connected = false, transport = "bt-disabled"))
                    delay(5000)
                    continue
                }

                // 1. Try Classic Bluetooth SPP for paired ANDROID-VLINK / OBD devices
                var sppSuccess = false
                try {
                    val obdDevice = findPairedObdDevice(adapter)
                    if (obdDevice != null) {
                        sppSuccess = runClassicSppSession(adapter, obdDevice)
                    }
                } catch (_: SecurityException) {
                    emitState(ObdState(connected = false, transport = "no-permission", lastError = "Permiso Bluetooth requerido"))
                } catch (e: Throwable) {
                    Log.d(TAG, "SPP attempt ended: ${e.message}")
                }

                if (!isActive || !isRunning.get()) break

                // 2. Try BLE GATT Scan & Connect (IOS-VLINK / Vgate 18F0) if SPP didn't stay connected
                if (!sppSuccess) {
                    try {
                        runBleGattScanAndSession(adapter)
                    } catch (_: SecurityException) {
                        emitState(ObdState(connected = false, transport = "no-permission", lastError = "Permiso Bluetooth/Ubicación requerido"))
                    } catch (e: Throwable) {
                        Log.d(TAG, "BLE GATT attempt ended: ${e.message}")
                    }
                }

                if (!isActive || !isRunning.get()) break
                delay(6000)
            }
        }
    }

    fun triggerReconnect() {
        val scope = parentScope ?: return
        stop()
        start(scope)
    }

    fun stop() {
        isRunning.set(false)
        connectionJob?.cancel()
        connectionJob = null
        closeSpp()
        closeGatt()
        emitState(ObdState(connected = false, transport = "disconnected"))
    }

    private fun emitState(state: ObdState) {
        currentState = state
        onTelemetry(state)
    }

    @Suppress("MissingPermission")
    private fun findPairedObdDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = try { adapter.bondedDevices } catch (_: Throwable) { emptySet() } ?: emptySet()
        return bonded.firstOrNull { dev ->
            val name = dev.name ?: ""
            // Prefer Classic/Dual device if name isn't exclusively "IOS-VLINK"
            matchesObdDeviceName(name) && !name.equals("IOS-VLINK", ignoreCase = true)
        } ?: bonded.firstOrNull { matchesObdDeviceName(it.name) }
    }

    @Suppress("MissingPermission")
    private fun runClassicSppSession(adapter: BluetoothAdapter, device: BluetoothDevice): Boolean {
        val devName = try { device.name ?: "Vgate OBD2" } catch (_: Throwable) { "Vgate OBD2" }
        var socket: BluetoothSocket? = null
        return try {
            try { adapter.cancelDiscovery() } catch (_: Throwable) {}
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            sppSocket = socket

            val input = socket.inputStream
            val output = socket.outputStream
            val sendFn: suspend (String) -> String = { cmd ->
                sendSppCommand(input, output, cmd)
            }
            initializeAndPollElm327(devName, "bt-spp", sendFn)
            true
        } catch (e: Throwable) {
            Log.d(TAG, "Classic SPP connect failed ($devName): ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
            sppSocket = null
        }
    }

    private fun sendSppCommand(input: InputStream, output: OutputStream, cmd: String): String {
        // Drain any stale bytes
        while (input.available() > 0) input.read()
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 900) {
            if (input.available() > 0) {
                val b = input.read()
                if (b == -1) break
                val ch = b.toChar()
                if (ch == '>') break
                sb.append(ch)
            } else {
                Thread.sleep(15)
            }
        }
        return sb.toString().trim()
    }

    @Suppress("MissingPermission")
    private suspend fun runBleGattScanAndSession(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        val foundDevice = CompletableDeferred<BluetoothDevice?>()
        val vgateParcel = ParcelUuid(VGATE_SVC_UUID)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val devName = result.scanRecord?.deviceName ?: try { dev.name } catch (_: Throwable) { null }
                val hasVgateUuid = result.scanRecord?.serviceUuids?.contains(vgateParcel) == true
                if (hasVgateUuid || matchesObdDeviceName(devName)) {
                    if (!foundDevice.isCompleted) foundDevice.complete(dev)
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)
        val targetDevice = withTimeoutOrNull(5500) { foundDevice.await() }
        try { scanner.stopScan(callback) } catch (_: Throwable) {}

        if (targetDevice == null) {
            emitState(currentState.copy(connected = false, transport = "ble-idle"))
            return
        }

        val devName = try { targetDevice.name ?: "IOS-VLINK BLE" } catch (_: Throwable) { "IOS-VLINK BLE" }
        val connectedReady = CompletableDeferred<Boolean>()
        gattReady.set(false)
        gattWriteChar = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "BLE GATT connected to $devName, discovering services...")
                    gatt.discoverServices()
                } else {
                    gattReady.set(false)
                    if (!connectedReady.isCompleted) connectedReady.complete(false)
                    pendingGattCmd?.complete("")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (!connectedReady.isCompleted) connectedReady.complete(false)
                    return
                }
                val setupOk = configureGattCharacteristics(gatt)
                if (!setupOk && !connectedReady.isCompleted) {
                    connectedReady.complete(false)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                gattReady.set(status == BluetoothGatt.GATT_SUCCESS)
                if (!connectedReady.isCompleted) connectedReady.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val chunk = characteristic.value?.toString(Charsets.US_ASCII) ?: return
                synchronized(gattBuffer) {
                    gattBuffer.append(chunk)
                    val endIdx = gattBuffer.indexOf(">")
                    if (endIdx >= 0) {
                        val resp = gattBuffer.substring(0, endIdx).trim()
                        gattBuffer.clear()
                        pendingGattCmd?.complete(resp)
                    }
                }
            }
        }

        val gatt = try {
            targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Throwable) {
            Log.w(TAG, "connectGatt error: ${e.message}")
            null
        }
        if (gatt == null) return
        activeGatt = gatt

        try {
            val ready = withTimeoutOrNull(8000) { connectedReady.await() } ?: false
            if (!ready || gattWriteChar == null) {
                Log.d(TAG, "BLE GATT setup timed out or incompatible on $devName")
                return
            }
            initializeAndPollElm327(devName, "ble-gatt") { cmd ->
                sendGattCommand(gatt, cmd)
            }
        } finally {
            closeGatt()
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun configureGattCharacteristics(gatt: BluetoothGatt): Boolean {
        val candidates = listOf(
            Triple(VGATE_SVC_UUID, VGATE_NOTIFY_UUID, VGATE_WRITE_UUID),
            Triple(FFE0_SVC_UUID, FFE1_CHAR_UUID, FFE1_CHAR_UUID),
            Triple(FFF0_SVC_UUID, FFF1_NOTIFY_UUID, FFF2_WRITE_UUID)
        )
        var notifyChar: BluetoothGattCharacteristic? = null
        var writeChar: BluetoothGattCharacteristic? = null

        for ((svcId, nId, wId) in candidates) {
            val svc = gatt.getService(svcId) ?: continue
            val n = svc.getCharacteristic(nId)
            val w = svc.getCharacteristic(wId)
            if (n != null && w != null) {
                notifyChar = n
                writeChar = w
                break
            }
        }
        if (notifyChar == null || writeChar == null) return false

        gattWriteChar = writeChar
        gatt.setCharacteristicNotification(notifyChar, true)
        val cccd = notifyChar.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            return gatt.writeDescriptor(cccd)
        }
        gattReady.set(true)
        return true
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun sendGattCommand(gatt: BluetoothGatt, cmd: String): String {
        val ch = gattWriteChar ?: return ""
        if (!gattReady.get()) return ""

        val deferred = CompletableDeferred<String>()
        pendingGattCmd = deferred
        synchronized(gattBuffer) { gattBuffer.clear() }

        val bytes = "$cmd\r".toByteArray(Charsets.US_ASCII)
        val hasNoResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        ch.writeType = if (hasNoResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = bytes
        if (!gatt.writeCharacteristic(ch)) {
            pendingGattCmd = null
            return ""
        }
        val res = withTimeoutOrNull(1400) { deferred.await() } ?: ""
        pendingGattCmd = null
        return res
    }

    private suspend fun initializeAndPollElm327(
        devName: String,
        transport: String,
        sendCmd: suspend (String) -> String
    ) {
        sendCmd("ATZ")
        delay(280)
        sendCmd("ATE0")
        sendCmd("ATL0")
        sendCmd("ATS0")
        sendCmd("ATST32")
        sendCmd("ATSP0")

        var voltage = parseVoltage(sendCmd("ATRV"))
        var rpm = 0
        var coolantC = -99
        var obdSpeed = 0
        var loadPct = -1
        var kmL = -1.0
        var mapKpa = -1
        var iatK = 298.0
        var hasMafPid = true
        var emptyCycles = 0

        emitState(
            ObdState(
                rpm = rpm,
                coolantTempC = coolantC,
                speedKmh = obdSpeed,
                batteryVoltage = voltage,
                engineLoadPct = loadPct,
                consumptionKmL = kmL,
                connected = true,
                transport = transport,
                deviceName = devName
            )
        )

        var tick = 0
        while (isRunning.get() && (transport != "ble-gatt" || gattReady.get())) {
            val rpmHex = sendCmd("010C")
            val parsedRpm = parseRpm(rpmHex)
            if (parsedRpm >= 0) {
                rpm = parsedRpm
                emptyCycles = 0
            } else {
                emptyCycles++
            }

            val spdHex = sendCmd("010D")
            val parsedSpd = parseSingleBytePid(spdHex, "410D")
            if (parsedSpd >= 0) obdSpeed = parsedSpd

            if (tick % 3 == 0) {
                val tempHex = sendCmd("0105")
                val parsedTemp = parseCoolantTemp(tempHex)
                if (parsedTemp > -90) coolantC = parsedTemp

                val loadHex = sendCmd("0104")
                val parsedLoad = parseSingleBytePid(loadHex, "4104")
                if (parsedLoad >= 0) loadPct = ((parsedLoad * 100.0) / 255.0).toInt()
            }

            if (tick % 4 == 0) {
                val v = parseVoltage(sendCmd("ATRV"))
                if (v > 5.0) voltage = v
            }

            // Fuel economy: Prefer physical MAF (0110), fallback to MAP (010B) + IAT (010F) Speed-Density (1.4L 8V Chevrolet Joy)
            var instantMafGps = -1.0
            if (hasMafPid) {
                val mafHex = sendCmd("0110")
                val m = parseTwoBytePid(mafHex, "4110")
                if (m >= 0) {
                    instantMafGps = m / 100.0
                } else if (mafHex.contains("NO DATA", true) || mafHex.contains("?", true)) {
                    hasMafPid = false
                }
            }
            if (!hasMafPid && tick % 2 == 0) {
                val mapVal = parseSingleBytePid(sendCmd("010B"), "410B")
                if (mapVal > 0) mapKpa = mapVal
                if (tick % 8 == 0) {
                    val iatRaw = parseSingleBytePid(sendCmd("010F"), "410F")
                    if (iatRaw >= 0) iatK = (iatRaw - 40 + 273.15).coerceIn(240.0, 360.0)
                }
                if (rpm > 350 && mapKpa > 10) {
                    val imap = (rpm * mapKpa) / (iatK * 2.0)
                    instantMafGps = (imap / 60.0) * 0.84 * 1.389 * 28.97 / 8.314
                }
            }

            if (instantMafGps > 0.2 && obdSpeed > 3) {
                val litersPerHour = (instantMafGps / 14.7) * 3600.0 / 740.0
                if (litersPerHour > 0.05) {
                    val instantKmL = (obdSpeed / litersPerHour).coerceIn(2.0, 38.0)
                    kmL = if (kmL > 0.0) (kmL * 0.72 + instantKmL * 0.28) else instantKmL
                }
            }

            // If transport died completely (e.g. car ignition turned off / dongle slept)
            if (emptyCycles > 20 && parseVoltage(sendCmd("ATRV")) < 0) {
                break
            }

            emitState(
                ObdState(
                    rpm = rpm,
                    coolantTempC = coolantC,
                    speedKmh = obdSpeed,
                    batteryVoltage = voltage,
                    engineLoadPct = loadPct,
                    consumptionKmL = kmL,
                    connected = true,
                    transport = transport,
                    deviceName = devName
                )
            )
            tick++
            delay(280)
        }
    }

    private fun extractPidBytes(raw: String, header: String): List<Int>? {
        val clean = raw.uppercase(Locale.US)
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
        val idx = clean.indexOf(header)
        if (idx < 0) return null
        val tail = clean.substring(idx + header.length)
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < tail.length && out.size < 4) {
            val byteStr = tail.substring(i, i + 2)
            val v = byteStr.toIntOrNull(16) ?: break
            out.add(v)
            i += 2
        }
        return if (out.isNotEmpty()) out else null
    }

    private fun parseRpm(raw: String): Int {
        val b = extractPidBytes(raw, "410C") ?: return -1
        if (b.size < 2) return -1
        return ((b[0] * 256) + b[1]) / 4
    }

    private fun parseCoolantTemp(raw: String): Int {
        val b = extractPidBytes(raw, "4105") ?: return -99
        return b[0] - 40
    }

    private fun parseSingleBytePid(raw: String, header: String): Int {
        val b = extractPidBytes(raw, header) ?: return -1
        return b[0]
    }

    private fun parseTwoBytePid(raw: String, header: String): Int {
        val b = extractPidBytes(raw, header) ?: return -1
        if (b.size < 2) return -1
        return (b[0] * 256) + b[1]
    }

    private fun parseVoltage(raw: String): Double {
        val match = Regex("([0-9]+\\.?[0-9]*)").find(raw) ?: return -1.0
        val v = match.groupValues[1].toDoubleOrNull() ?: return -1.0
        return if (v in 5.0..18.5) v else -1.0
    }

    private fun closeSpp() {
        try { sppSocket?.close() } catch (_: Throwable) {}
        sppSocket = null
    }

    @Suppress("MissingPermission")
    private fun closeGatt() {
        gattReady.set(false)
        pendingGattCmd?.cancel()
        pendingGattCmd = null
        try { activeGatt?.disconnect() } catch (_: Throwable) {}
        try { activeGatt?.close() } catch (_: Throwable) {}
        activeGatt = null
        gattWriteChar = null
    }
}
