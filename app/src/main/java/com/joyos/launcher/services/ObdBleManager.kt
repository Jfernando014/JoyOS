package com.joyos.launcher.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
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
 * Automatically exits JoyOS "Sin Lector" (GPS-only) mode when live ECU telemetry arrives.
 */
class ObdBleManager(
    context: Context,
    private val onObdData: (rpm: Int, coolantTempC: Int, voltage: String, connected: Boolean) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var pollingJob: Job? = null
    private var socket: BluetoothSocket? = null
    var isConnected: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isConnected) {
                    isConnected = tryConnectVgate()
                    if (!isConnected) {
                        delay(6000L) // Retry every 6s in background while in GPS-only mode
                        continue
                    }
                }

                try {
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
                } catch (_: Exception) {
                    disconnect()
                    onObdData(0, 88, "14,1 V", false)
                }
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectVgate(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false

        return try {
            val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
            val vgateDevice = pairedDevices.firstOrNull { dev ->
                val name = (dev.name ?: "").lowercase()
                name.contains("vlink") || name.contains("vgate") || name.contains("icar") || name.contains("obd")
            } ?: return false

            val btSocket = vgateDevice.createRfcommSocketToServiceRecord(sppUuid)
            adapter.cancelDiscovery()
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
        } catch (_: Exception) {
            disconnect()
            false
        }
    }

    private fun queryRpm(out: OutputStream, inp: InputStream): Int {
        val resp = sendCommand(out, inp, "010C")
        val clean = resp.replace(" ", "").uppercase()
        val idx = clean.indexOf("410C")
        if (idx != -1 && clean.length >= idx + 8) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return -1
            val b = clean.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return -1
            return ((a * 256) + b) / 4
        }
        return -1
    }

    private fun queryCoolantTemp(out: OutputStream, inp: InputStream): Int {
        val resp = sendCommand(out, inp, "0105")
        val clean = resp.replace(" ", "").uppercase()
        val idx = clean.indexOf("4105")
        if (idx != -1 && clean.length >= idx + 6) {
            val a = clean.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return 88
            return a - 40
        }
        return 88
    }

    private fun queryVoltage(out: OutputStream, inp: InputStream): String {
        val resp = sendCommand(out, inp, "ATRV").trim()
        return if (resp.endsWith("V", ignoreCase = true) && resp.length <= 7) {
            resp.uppercase().replace(".", ",")
        } else {
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
        } catch (_: Exception) {
        }
        socket = null
        isConnected = false
    }
}
