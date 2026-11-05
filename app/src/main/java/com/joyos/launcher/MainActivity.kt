package com.joyos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joyos.launcher.bridge.AndroidBridge
import com.joyos.launcher.services.GpsTelemetryManager
import com.joyos.launcher.services.JoyAudioDspEngine
import com.joyos.launcher.services.ObdBleManager
import com.joyos.launcher.services.TpmsUsbReceiver
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private lateinit var dspEngine: JoyAudioDspEngine
    private lateinit var gpsManager: GpsTelemetryManager
    private lateinit var obdManager: ObdBleManager
    private lateinit var tpmsReceiver: TpmsUsbReceiver

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install uncaught exception guard to capture and prevent fatal launch crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JoyOS", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            hideSystemUI()
        } catch (e: Throwable) {
            Log.w("JoyOS", "System UI hide warning: ${e.message}")
        }

        setContentView(R.layout.activity_main)

        dspEngine = JoyAudioDspEngine(this)
        bridge = AndroidBridge(this, dspEngine)
        webView = findViewById(R.id.webView)

        gpsManager = GpsTelemetryManager(this) { speedKmh, tripKm ->
            try {
                dspEngine.onSpeedUpdated(speedKmh)
            } catch (_: Throwable) {}
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate({ speed: $speedKmh, tripKm: ${"%.1f".format(tripKm)} });",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }

        obdManager = ObdBleManager(this) { rpm, coolantTempC, _, connected ->
            runOnUiThread {
                try {
                    val hot = coolantTempC >= 104
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate({ rpm: $rpm, engineHot: $hot, obdConnected: $connected });",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }

        tpmsReceiver = TpmsUsbReceiver(this) { _, _, _, _, lowAlert, connected ->
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate({ tireAlert: $lowAlert, tpmsConnected: $connected });",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }

        configureWebView()
        loadJoyOS()
        ensureAutomotivePermissions()

        // Handle back button: as a Home Launcher, navigate back to home view rather than exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    webView.evaluateJavascript("if (typeof switchTab === 'function') switchTab('home');", null)
                } catch (_: Throwable) {}
            }
        })
    }

    override fun onResume() {
        super.onResume()
        try { hideSystemUI() } catch (_: Throwable) {}
        try { gpsManager.startTracking() } catch (_: Throwable) {}
        try { obdManager.start(lifecycleScope) } catch (_: Throwable) {}
        try { tpmsReceiver.register() } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        try { gpsManager.stopTracking() } catch (_: Throwable) {}
        try { obdManager.stop() } catch (_: Throwable) {}
        try { tpmsReceiver.unregister() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { dspEngine.release() } catch (_: Throwable) {}
    }

    private fun ensureAutomotivePermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 1001)
        } else {
            try { gpsManager.startTracking() } catch (_: Throwable) {}
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            try {
                gpsManager.startTracking()
            } catch (_: Throwable) {}
            try {
                obdManager.start(lifecycleScope)
            } catch (_: Throwable) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF0A0E17.toInt())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                } catch (_: Throwable) {}
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                displayZoomControls = false
                builtInZoomControls = false
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    try {
                        val initialVol = bridge.getMasterVolume()
                        evaluateJavascript(
                            "if (typeof state !== 'undefined') { state.vol = $initialVol; if (typeof renderAll === 'function') renderAll(); } if (typeof initNativeHardwareMode === 'function') initNativeHardwareMode();",
                            null
                        )
                    } catch (_: Throwable) {}
                }
            }

            addJavascriptInterface(bridge, "AndroidBridge")
        }
    }

    private fun loadJoyOS() {
        try {
            // Priority 1: Check if a hot-update exists in internal storage
            val liveUpdateFile = File(filesDir, "joy_os_live.html")
            if (liveUpdateFile.exists() && liveUpdateFile.length() > 1024) {
                webView.loadUrl("file://${liveUpdateFile.absolutePath}")
                return
            }

            // Priority 2: Load bundled production assets from assets/www/index.html
            webView.loadUrl("file:///android_asset/www/index.html")
        } catch (e: Throwable) {
            Log.e("JoyOS", "Failed to load JoyOS webview content: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
}
