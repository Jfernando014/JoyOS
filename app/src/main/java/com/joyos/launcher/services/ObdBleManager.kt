package com.joyos.launcher.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages Bluetooth connection and ELM327 polling for Vgate iCar Pro 4.0 (Android-Vlink / IOS-Vlink).
 * Automatically falls back safely to "Sin Lector" (GPS-only) mode when permissions are missing
 * or when hardware is not paired.
 */
class ObdBleManager(
    private val context: Context,
    private val onObdData: (rpm: Int, coolantTempC: Int, voltage: String, connected: Boolean) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var pollingJob: Job? = null
    private var socket: BluetoothSocket? = null
    var isConnected: Boolean = false
        private set

    private val safeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w("ObdBleManager", "Safe recovery from Bluetooth exception: ${throwable.message}")
        disconnect()
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun start(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO + safeExceptionHandler) {
            while (isActive) {
                try {
                    if (!hasBluetoothPermission()) {
                        delay(6000L)
                        continue
                    }

                    if (!isConnected) {
                        isConnected = tryConnectVgate()
                        if (!isConnected) {
                            delay(6000L) // Retry every 6s in background while in GPS-only mode
                            continue
                        }
                    }

                    val input = socket?.inputStream
                    val output = socket?.outputStream
                    if (input == null || output == null) {
                        disconnect()
                        continue
                    }

                    val rpm = queryRpm(output, input)
                    val tempC = queryCoolantTemp(output, input)
                    val volt = queryVoltage(output, input)

                    if (rpm >= 0) {
                        onObdData(rpm, tempC, volt, true)
                    }
                    delay(180L) // ~5.5 Hz smooth needle refresh rate
                } catch (e: Throwable) {
                    disconnect()
                    onObdData(0, 88, "14,1 V", false)
                    delay(5000L)
                }
            }
        }
    }

    fun stop() {
        try {
            pollingJob?.cancel()
        } catch (_: Throwable) {}
        pollingJob = null
        disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectVgate(): Boolean {
        if (!hasBluetoothPermission()) return false

        return try {
            val adapter = bluetoothAdapter ?: return false
            if (!adapter.isEnabled) return false

            val pairedDevices: Set<BluetoothDevice> = try {
                adapter.bondedDevices ?: emptySet()
            } catch (_: Throwable) {
                emptySet()
            }

            val vgateDevice = pairedDevices.firstOrNull { dev ->
                val name = try { (dev.name ?: "").lowercase() } catch (_: Throwable) { "" }
                name.contains("vlink") || name.contains("vgate") || name.contains("icar") || name.contains("obd")
            } ?: return false

            val btSocket = vgateDevice.createRfcommSocketToServiceRecord(sppUuid)
            try { adapter.cancelDiscovery() } catch (_: Throwable) {}
            btSocket.connect()
            socket = btSocket

            val out = btSocket.outputStream
            val inp = btSocket.inputStream
            // Initialize ELM327 protocol for Chevrolet Joy 1.4L (ISO 15765-4 CAN 11/500)
            sendCommand(out, inp, "ATZ")
            sendCommand(out, inp, "ATE0")
            sendCommand(out, inp, "ATL0")
            sendCommand(out, inp, "ATSP0")
            true
        } catch (_: Throwable) {
            disconnect()
            false
        }
    }

    private fun queryRpm(out: OutputStream, inp: InputStream): Int {
        return try {
            val resp = sendCommand(out, inp, "010C")
            val clean = resp.replace(" ", "").uppercase()
            val idx = clean.indexOf("410C")
            if (idx != -1 && clean.length >= idx + 8) {
                val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
                val b = clean.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return -1
                ((a * 256) + b) / 4
            } else {
                -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun queryCoolantTemp(out: OutputStream, inp: InputStream): Int {
        return try {
            val resp = sendCommand(out, inp, "0105")
            val clean = resp.replace(" ", "").uppercase()
            val idx = clean.indexOf("4105")
            if (idx != -1 && clean.length >= idx + 6) {
                val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return 88
                a - 40
            } else {
                88
            }
        } catch (_: Throwable) {
            88
        }
    }

    private fun queryVoltage(out: OutputStream, inp: InputStream): String {
        return try {
            val resp = sendCommand(out, inp, "ATRV").trim()
            if (resp.endsWith("V", ignoreCase = true) && resp.length <= 7) {
                resp.uppercase().replace(".", ",")
            } else {
                "14,2 V"
            }
        } catch (_: Throwable) {
            "14,2 V"
        }
    }

    private fun sendCommand(out: OutputStream, inp: InputStream, cmd: String): String {
        out.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        out.flush()

        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 400L) {
            if (inp.available() > 0) {
                val c = inp.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                Thread.sleep(10L)
            }
        }
        return sb.toString().trim()
    }

    private fun disconnect() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        isConnected = false
    }
}
