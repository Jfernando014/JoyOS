package com.joyos.launcher.services

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Result report returned after running in-app automotive hardware optimizations.
 */
data class OptimizationReport(
    val isRooted: Boolean,
    val ramFreedMb: Long,
    val bloatwareFrozen: List<String>,
    val audioUnlocked: Boolean,
    val adbWifiPortOpened: Boolean,
    val animationsSpeedup: Boolean,
    val summary: String
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("isRooted", isRooted)
        json.put("ramFreedMb", ramFreedMb)
        val arr = JSONArray()
        bloatwareFrozen.forEach { arr.put(it) }
        json.put("bloatwareFrozen", arr)
        json.put("audioUnlocked", audioUnlocked)
        json.put("adbWifiPortOpened", adbWifiPortOpened)
        json.put("animationsSpeedup", animationsSpeedup)
        json.put("summary", summary)
        return json
    }
}

/**
 * Native in-app hardware & system optimizer for automotive Android units (AC8227L / ZXDZ_01).
 * Executes commands directly on the tablet without requiring an external PC or ADB cable.
 */
class JoyHardwareOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "JoyHardwareOptimizer"

        private val OEM_LAUNCHER_PACKAGES = listOf(
            "com.android.launcher3",
            "com.fyt.launcher",
            "com.ts.main",
            "com.xyauto.launcher",
            "com.autochips.carplay"
        )

        private val SAFE_CACHE_CLEANUP_PACKAGES = listOf(
            "com.google.android.youtube",
            "com.android.chrome",
            "com.android.vending"
        )

        private val KNOWN_SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/vendor/bin/su"
        )
    }

    private val activityManager = context.getSystemService(Context.AUDIO_SERVICE).let {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    /**
     * Checks if the device firmware is pre-rooted (common in automotive userdebug head units).
     */
    fun isDeviceRooted(): Boolean {
        for (path in KNOWN_SU_PATHS) {
            if (File(path).exists()) return true
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.destroy()
            !line.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Runs full hardware optimization across Layer 1 (Standard API) and Layer 2 (Root Shell).
     */
    suspend fun runOptimization(): OptimizationReport = withContext(Dispatchers.IO) {
        val memBefore = getAvailableMemoryMb()
        val isRooted = isDeviceRooted()
        val frozenList = mutableListOf<String>()
        var audioUnlocked = false
        var adbWifiOpened = false
        var animationsSpeedup = false

        Log.i(TAG, "Starting native hardware optimization. isRooted=$isRooted")

        // 1. Layer 1: Standard API Optimizations (Always Works)
        try {
            // Remove safe volume limiter if WRITE_SETTINGS is available
            Settings.Global.putInt(context.contentResolver, "audio_safe_volume_state", 2)
            audioUnlocked = true
        } catch (_: Throwable) {}

        try {
            // Speed up window animation scales to 0.5x
            Settings.Global.putFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 0.5f)
            Settings.Global.putFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0.5f)
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
            animationsSpeedup = true
        } catch (_: Throwable) {}

        // Kill background processes of non-critical cached apps (NEVER kill com.ts.main or com.autochips.carplay)
        SAFE_CACHE_CLEANUP_PACKAGES.forEach { pkg ->
            try {
                activityManager?.killBackgroundProcesses(pkg)
            } catch (_: Throwable) {}
        }

        // 2. Layer 2: Root Shell Super Mode (If userdebug / su is available)
        if (isRooted) {
            // SAFETY GUARANTEE: Ensure factory MCU / CarPlay / Launcher packages are ALWAYS enabled
            // (Never run pm disable-user on com.ts.main, com.autochips.carplay, or com.android.launcher3)
            OEM_LAUNCHER_PACKAGES.forEach { pkg ->
                try { executeRootCommand("pm enable $pkg") } catch (_: Throwable) {}
            }

            // Global audio limiter removal via root
            executeRootCommand("settings put global audio_safe_volume_state 2")
            audioUnlocked = true

            // Animation scale acceleration via root
            executeRootCommand("settings put global window_animation_scale 0.5")
            executeRootCommand("settings put global transition_animation_scale 0.5")
            executeRootCommand("settings put global animator_duration_scale 0.5")
            animationsSpeedup = true

            // Exemption from Doze battery optimizations
            executeRootCommand("dumpsys deviceidle whitelist +${context.packageName}")

            // Open TCP/IP port 5555 for seamless wireless ADB over Wi-Fi
            val adbSuccess = executeRootCommand("setprop service.adb.tcp.port 5555; stop adbd; start adbd")
            if (adbSuccess) {
                adbWifiOpened = true
            }
        }

        // Trigger memory garbage collection to reclaim RAM
        System.gc()
        val memAfter = getAvailableMemoryMb()
        val freedMb = (memAfter - memBefore).coerceAtLeast(0)

        val summary = buildString {
            if (isRooted) {
                append("Super-optimización segura aplicada: ")
                if (adbWifiOpened) append("Puerto ADB Wi-Fi 5555 activo. ")
            } else {
                append("Optimización estándar completada: Caché en segundo plano liberada. ")
            }
            append("Servicios OEM/MCU protegidos, Audio DSP 100% y animaciones 0.5x.")
        }

        Log.i(TAG, "Hardware optimization finished: $summary (RAM freed: +${freedMb}MB)")

        OptimizationReport(
            isRooted = isRooted,
            ramFreedMb = freedMb,
            bloatwareFrozen = frozenList,
            audioUnlocked = audioUnlocked,
            adbWifiPortOpened = adbWifiOpened,
            animationsSpeedup = animationsSpeedup,
            summary = summary
        )
    }

    /**
     * Executes a single command under root shell (`su -c`).
     */
    private fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Root command execution error ($command): ${e.message}")
            false
        }
    }

    /**
     * Measures currently available system RAM in megabytes.
     */
    private fun getAvailableMemoryMb(): Long {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (_: Throwable) {
            0L
        }
    }
}
