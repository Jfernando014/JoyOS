package com.joyos.launcher

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
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
import com.joyos.launcher.bridge.AndroidBridge
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        bridge = AndroidBridge(this)

        configureWebView()
        loadJoyOS()

        // Handle back button: as a Home Launcher, do not exit the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Inform web view or navigate to home tab
                webView.evaluateJavascript("if (typeof switchTab === 'function') switchTab('home');", null)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF0A0E17.toInt())

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
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
                    // Sync initial system volume and settings
                    val initialVol = bridge.getMasterVolume()
                    evaluateJavascript("if (typeof state !== 'undefined') { state.vol = $initialVol; if (typeof renderAll === 'function') renderAll(); }", null)
                }
            }

            addJavascriptInterface(bridge, "AndroidBridge")
        }
    }

    private fun loadJoyOS() {
        // Priority 1: Check if a hot-update exists in internal storage
        val liveUpdateFile = File(filesDir, "joy_os_live.html")
        if (liveUpdateFile.exists() && liveUpdateFile.length() > 1024) {
            webView.loadUrl("file://${liveUpdateFile.absolutePath}")
            return
        }

        // Priority 2: Load bundled production assets from assets/www/index.html
        webView.loadUrl("file:///android_asset/www/index.html")
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
