package com.patnaik.sam

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private lateinit var container: FrameLayout
    private var crashCount = 0
    private val MAX_CRASHES = 3

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore — FGS still runs without POST_NOTIFICATIONS */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on while the activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = findViewById(R.id.web_container)

        requestNotificationPermissionIfNeeded()
        promptBatteryOptimizationExemption()
        startPollerService()
        buildAndInflateWebView(savedInstanceState)
        maybePromptOemAutoStart()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndInflateWebView(savedInstanceState: Bundle?) {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView = wv
        container.addView(wv)

        val s = wv.settings
        // Core
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // Viewport
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        // Media
        s.mediaPlaybackRequiresUserGesture = false
        // Network
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // File access (we load from android_asset so allow the minimal set)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.textZoom = 100
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = false

        // Offscreen pre-raster: biggest native rendering win — raster off-screen tiles ahead of time
        if (WebViewFeature.isFeatureSupported("OFFSCREEN_PRE_RASTER")) {
            try { WebSettingsCompat.setOffscreenPreRaster(s, true) } catch (_: Throwable) {}
        }
        // Safe browsing adds latency to every fetch — disable for offline-asset app
        try {
            if (WebViewFeature.isFeatureSupported("SAFE_BROWSING_ENABLE")) {
                WebSettingsCompat.setSafeBrowsingEnabled(s, false)
            }
        } catch (_: Throwable) {}
        // Dark mode follows system
        try {
            if (WebViewFeature.isFeatureSupported("FORCE_DARK")) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_AUTO)
            }
        } catch (_: Throwable) {}

        // THE key always-alive flag: keep renderer bound at IMPORTANT priority AND don't waive
        // when the WebView is briefly off-screen (notification shade, orientation change, etc.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                /* waivedWhenNotVisible = */ false
            )
        }

        // Cookies for Firebase auth flows
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // chrome://inspect debugging from a tethered PC (always on for sideloaded debug APK)
        WebView.setWebContentsDebuggingEnabled(true)

        wv.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Keep all nav inside the WebView
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Inject lifecycle hooks the native onTrimMemory() bridge calls into.
                // The SPA doesn't need to define window.app itself — it's safe to override here
                // because the SPA uses its own internal state objects.
                val bootstrapJs = """
                    (function(){
                      if (!window.app) window.app = {};
                      window.app.onLowMemory = function(level) {
                        try {
                          console.log('[SAM-Native] onLowMemory level=' + level);
                          // Force a V8 GC pass via performance.measureUserAgentSpecificMemory if available
                          if (performance.measureUserAgentSpecificMemory) {
                            performance.measureUserAgentSpecificMemory().catch(function(){});
                          }
                          // Give caches a chance to shrink — SMS cache + inflight fetch map
                          try { if (typeof smsAnalysisCache !== 'undefined' && smsAnalysisCache.clear) smsAnalysisCache.clear(); } catch(e){}
                          try { if (typeof _inflightFetch !== 'undefined' && _inflightFetch.clear) _inflightFetch.clear(); } catch(e){}
                        } catch(e) {}
                      };
                      window.app.onUiHidden = function() {
                        try { console.log('[SAM-Native] UI hidden'); } catch(e) {}
                      };
                      // Hard pause the polling loop when tab goes to background
                      document.addEventListener('visibilitychange', function(){
                        try {
                          if (window.SAMNative && window.SAMNative.log) {
                            window.SAMNative.log('visibility=' + document.visibilityState);
                          }
                        } catch(e){}
                      });
                    })();
                """.trimIndent()
                view.evaluateJavascript(bootstrapJs, null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val didCrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail.didCrash()
                Log.e("SAM-WV", if (didCrash) "Renderer process CRASHED" else "System killed renderer for memory reclaim")

                // Rebuild WebView — MUST remove + destroy the dead one first
                (view.parent as? ViewGroup)?.removeView(view)
                try { view.destroy() } catch (_: Throwable) {}
                webView = null

                if (crashCount < MAX_CRASHES) {
                    crashCount++
                    container.post { buildAndInflateWebView(null) }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "App crashed $crashCount times. Please force-stop and reopen.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return true  // CRITICAL: returning false here crashes the whole app
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let {
                    Log.d("SAM-JS", "[${it.messageLevel()}] ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }
        }

        // Download handler
        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimetype)
                req.addRequestHeader("User-Agent", userAgent)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })

        // Native <-> JS bridge for memory/visibility events
        wv.addJavascriptInterface(SamBridge(), "SAMNative")

        if (savedInstanceState != null) {
            wv.restoreState(savedInstanceState)
        } else {
            wv.loadUrl("file:///android_asset/index.html")
        }
    }

    // Bridge Android lifecycle memory warnings into the JS layer so the SPA can shrink its caches
    // BEFORE the Low Memory Killer gets to us. WebView.freeMemory() is deprecated and a no-op.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val js = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                "try{window.app&&window.app.onLowMemory&&window.app.onLowMemory($level)}catch(e){}"
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                "try{window.app&&window.app.onUiHidden&&window.app.onUiHidden()}catch(e){}"
            else -> null
        }
        js?.let { webView?.evaluateJavascript(it, null) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.clearHistory()
                wv.destroy()
            } catch (_: Throwable) {}
        }
        webView = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
    }

    private fun promptBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startPollerService() {
        try {
            val i = Intent(this, FirebasePollerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (t: Throwable) {
            Log.w("SAM", "startPollerService failed: ${t.message}")
        }
    }

    private fun maybePromptOemAutoStart() {
        // Only prompt once ever — persisted in SharedPreferences
        val prefs = getSharedPreferences("sam_app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("oem_autostart_prompted", false)) return
        val oem = (Build.MANUFACTURER ?: "").lowercase()
        if (OemAutoStart.needsPrompt(oem)) {
            // Show a dialog after a short delay so the user sees the panel first
            container.postDelayed({
                try {
                    val msg = OemAutoStart.messageFor(oem)
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Keep SAM Panel running")
                        .setMessage(msg)
                        .setPositiveButton("Open Settings") { _, _ ->
                            OemAutoStart.open(this)
                        }
                        .setNegativeButton("Skip") { _, _ -> }
                        .setCancelable(true)
                        .show()
                    prefs.edit().putBoolean("oem_autostart_prompted", true).apply()
                } catch (_: Throwable) {}
            }, 3000)
        } else {
            prefs.edit().putBoolean("oem_autostart_prompted", true).apply()
        }
    }
}
