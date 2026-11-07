package com.joyos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
import com.joyos.launcher.services.JoyHardwareOptimizer
import com.joyos.launcher.services.ObdBleManager
import com.joyos.launcher.services.OtaUpdateManager
import com.joyos.launcher.services.TpmsUsbReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private lateinit var dspEngine: JoyAudioDspEngine
    private lateinit var gpsManager: GpsTelemetryManager
    private lateinit var obdManager: ObdBleManager
    private lateinit var tpmsReceiver: TpmsUsbReceiver
    private lateinit var otaManager: OtaUpdateManager
    private lateinit var hardwareOptimizer: JoyHardwareOptimizer

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

        webView = findViewById(R.id.webView)
        dspEngine = JoyAudioDspEngine(this)
        otaManager = OtaUpdateManager(this)
        hardwareOptimizer = JoyHardwareOptimizer(this)

        gpsManager = GpsTelemetryManager(this) { data ->
            try {
                dspEngine.onSpeedUpdated(data.speedKmh)
            } catch (_: Throwable) {}
            runOnUiThread {
                try {
                    webView.evaluateJavascript(
                        "if (typeof window.onGpsUpdate === 'function') window.onGpsUpdate({ speed: ${data.speedKmh}, tripKm: ${"%.1f".format(data.tripKm)}, lat: ${data.latitude}, lon: ${data.longitude}, bearing: ${data.bearing}, altitude: ${data.altitude}, hasFix: ${data.hasFix} });",
                        null
                    )
                    webView.evaluateJavascript(
                        "if (typeof window.onTelemetryUpdate === 'function') window.onTelemetryUpdate({ speed: ${data.speedKmh}, tripKm: ${"%.1f".format(data.tripKm)} });",
                        null
                    )
                } catch (_: Throwable) {}
            }
        }

        bridge = AndroidBridge(
            context = this,
            dspEngine = dspEngine,
            otaManager = otaManager,
            hardwareOptimizer = hardwareOptimizer,
            getGpsData = { gpsManager.currentGpsData },
            onReloadUi = { runOnUiThread { loadJoyOS() } },
            onEvaluateJs = { script -> runOnUiThread { webView.evaluateJavascript(script, null) } }
        )

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
        registerAutomotiveMediaReceivers()
        scheduleBackgroundOtaCheck()

        // Handle back button: as a Home Launcher, navigate back to home view rather than exiting
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    webView.evaluateJavascript("if (typeof switchTab === 'function') switchTab('home');", null)
                } catch (_: Throwable) {}
            }
        })
    }

    private val btMediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                val action = intent.action ?: ""
                val extras = intent.extras
                var title: String? = null
                var artist: String? = null
                var album: String? = null
                var durationMs: Long = 0L
                var isPlaying: Boolean? = null

                if (extras != null) {
                    val titleKeys = listOf("track", "title", "music_title", "song_name", "bt_music_title", "android.intent.extra.TITLE", "media_title")
                    val artistKeys = listOf("artist", "music_artist", "singer_name", "bt_music_artist", "android.intent.extra.ARTIST", "media_artist")
                    val albumKeys = listOf("album", "music_album", "album_name", "bt_music_album", "android.intent.extra.ALBUM", "media_album")
                    val durKeys = listOf("duration", "total_time", "music_duration", "time_total")
                    val playKeys = listOf("playing", "isPlaying", "playstate", "play_state", "bt_play_state")

                    for (k in titleKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { title = v.trim(); break }
                    }
                    for (k in artistKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { artist = v.trim(); break }
                    }
                    for (k in albumKeys) {
                        val v = extras.getString(k)
                        if (!v.isNullOrBlank()) { album = v.trim(); break }
                    }
                    for (k in durKeys) {
                        val v = extras.get(k)
                        if (v is Number && v.toLong() > 0) { durationMs = v.toLong(); break }
                    }
                    for (k in playKeys) {
                        if (extras.containsKey(k)) {
                            val v = extras.get(k)
                            when (v) {
                                is Boolean -> isPlaying = v
                                is Number -> isPlaying = (v.toInt() == 1 || v.toInt() == 3)
                            }
                            if (isPlaying != null) break
                        }
                    }

                    // Check if a MediaMetadata parcelable was sent (AVRCP Controller standard)
                    try {
                        @Suppress("DEPRECATION")
                        val meta = extras.getParcelable<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                            ?: extras.getParcelable<MediaMetadata>("metadata")
                        if (meta != null) {
                            meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
                            meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.let { if (it.isNotBlank()) artist = it }
                            meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.let { if (it.isNotBlank()) album = it }
                            val d = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                            if (d > 0) durationMs = d
                        }
                    } catch (_: Throwable) {}

                    try {
                        @Suppress("DEPRECATION")
                        val pb = extras.getParcelable<PlaybackState>("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK")
                            ?: extras.getParcelable<PlaybackState>("playback_state")
                        if (pb != null) {
                            isPlaying = (pb.state == PlaybackState.STATE_PLAYING)
                        }
                    } catch (_: Throwable) {}
                }

                // Check radio frequency broadcasts from ZXDZ_01 / TS / XYAuto / AutoChips MCU
                if (action.contains("radio", true) || action.contains("fm", true)) {
                    val freqExtra = extras?.get("frequency") ?: extras?.get("freq") ?: extras?.get("cur_freq")
                    if (freqExtra is Number) {
                        val rawFreq = freqExtra.toDouble()
                        val mhz = if (rawFreq > 1000) rawFreq / 1000.0 else if (rawFreq > 200) rawFreq / 10.0 else rawFreq
                        if (mhz in 87.0..108.5) {
                            runOnUiThread {
                                webView.evaluateJavascript("if (typeof window.onMcuRadioUpdate === 'function') window.onMcuRadioUpdate($mhz);", null)
                            }
                        }
                    }
                }

                if (!title.isNullOrBlank() || isPlaying != null) {
                    val durSec = if (durationMs > 1000) (durationMs / 1000).toInt() else durationMs.toInt()
                    bridge.updateLiveBtMetadata(title, artist, album, durSec, isPlaying, action)
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "btMediaReceiver warning: ${e.message}")
            }
        }
    }

    private fun registerAutomotiveMediaReceivers() {
        try {
            val filter = IntentFilter().apply {
                // Standard Android & Music player intents
                addAction("com.android.music.metachanged")
                addAction("com.android.music.playstatechanged")
                addAction("com.android.music.playbackcomplete")
                addAction("com.android.music.queuechanged")
                // Android Bluetooth AVRCP Controller & A2DP Sink
                addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_CHANGED")
                addAction("android.bluetooth.avrcp-controller.profile.action.PLAYBACK_STATE_CHANGED")
                addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
                addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
                // MediaTek / AutoChips AC8227L / ZXDZ_01 / WJ / TS / XYAuto OEM Broadcasts
                addAction("com.autochips.bluetooth.AvrcpControllerService.ACTION_TRACK_EVENT")
                addAction("com.autochips.bluetooth.btmusic.ACTION_META_CHANGED")
                addAction("com.autochips.bluetooth.PbapClientService.ACTION_EVENT")
                addAction("com.ts.bt.music.metachanged")
                addAction("com.ts.bt.playstatechanged")
                addAction("com.xyauto.broadcast.bt.music.info")
                addAction("com.xyauto.broadcast.bt.playstate")
                addAction("com.microntek.bt.report")
                addAction("com.fyt.bt.music")
                // OEM FM Radio & MCU state broadcasts
                addAction("com.ts.fmradio.FREQUENCY_CHANGED")
                addAction("com.xyauto.broadcast.radio.info")
                addAction("com.autochips.fmradio.intent.action.FREQ_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(btMediaReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(btMediaReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.w("JoyOS", "Could not register media broadcast receiver: ${e.message}")
        }
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
        try { unregisterReceiver(btMediaReceiver) } catch (_: Throwable) {}
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
            // Check if a verified hot-update exists in internal storage; otherwise load bundled production assets
            val liveUpdateFile = File(filesDir, "joy_os_live.html")
            if (liveUpdateFile.exists() && liveUpdateFile.length() > 1024) {
                val htmlText = liveUpdateFile.readText(Charsets.UTF_8)
                // Only use hot-update if it contains the latest REA & Leaflet production engine
                if (htmlText.contains("onBtMusicMetadata") && htmlText.contains("inspectOemHardware")) {
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/www/",
                        htmlText,
                        "text/html",
                        "UTF-8",
                        null
                    )
                    return
                } else {
                    // Delete stale pre-production cached hot-update so bundled APK asset is used
                    try { liveUpdateFile.delete() } catch (_: Throwable) {}
                }
            }

            // Load bundled production assets from assets/www/index.html
            webView.loadUrl("file:///android_asset/www/index.html")
        } catch (e: Throwable) {
            Log.e("JoyOS", "Failed to load JoyOS webview content: ${e.message}")
            try { webView.loadUrl("file:///android_asset/www/index.html") } catch (_: Throwable) {}
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

    private fun scheduleBackgroundOtaCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(8000)
                val res = otaManager.checkLatestRelease(bridge.getAppVersion())
                if (res.isSuccess && res.getOrNull()?.isNewer == true) {
                    val info = res.getOrNull()!!
                    val json = JSONObject().apply {
                        put("status", "success")
                        put("isNewer", true)
                        put("version", info.version)
                        put("tagName", info.tagName)
                        put("changelog", info.changelog)
                        put("apkUrl", info.apkUrl ?: "")
                        put("liveHtmlUrl", info.liveHtmlUrl ?: "")
                    }
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if (typeof window.onOtaUpdateAvailable === 'function') window.onOtaUpdateAvailable($json);",
                            null
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "Silent background OTA check skipped: ${e.message}")
            }
        }
    }

    private fun scheduleBootHardwareOptimization() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(4000)
                val report = hardwareOptimizer.runOptimization()
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if (typeof window.onHardwareOptimizationComplete === 'function') window.onHardwareOptimizationComplete(${report.toJson()});",
                        null
                    )
                }
            } catch (e: Throwable) {
                Log.w("JoyOS", "Silent boot hardware optimization skipped: ${e.message}")
            }
        }
    }
}
