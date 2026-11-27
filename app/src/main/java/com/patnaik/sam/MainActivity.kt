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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import android.util.Log
import android.view.KeyEvent
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private lateinit var container: FrameLayout
    private var crashCount = 0
    private val MAX_CRASHES = 3

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = findViewById(R.id.web_container)

        // Build WebView FIRST — no auto permission prompts, no auto service start.
        // Everything that could crash on startup runs AFTER the panel is visible.
        buildAndInflateWebView(savedInstanceState)

        // Defer all setup prompts by 5 seconds so the panel is visible first
        Handler(Looper.getMainLooper()).postDelayed({
            try { startPollerService() } catch (t: Throwable) { Log.w("SAM", "poller start failed: ${t.message}") }
        }, 5000)

        // ─── ROOT-ONLY WebView protection ───
        // If su is available, pin oom_score_adj=-1000 on the app + WebView renderers
        // so Android LMK physically cannot kill them. First call triggers one Magisk
        // permission prompt; silent forever after. No-op if device isn't rooted.
        Handler(Looper.getMainLooper()).postDelayed({
            Thread {
                try {
                    if (RootProtection.isRooted()) {
                        val result = RootProtection.applyProtection(this)
                        Log.i("SAM-Root", result)
                        runOnUiThread {
                            Toast.makeText(this, "🛡️ Root protection active — WebView pinned", Toast.LENGTH_SHORT).show()
                        }
                        // Re-apply every 60s so new WebView renderers also get pinned
                        RootProtection.startPeriodicReapply(this, 60_000)
                    } else {
                        Log.i("SAM-Root", "no root — skipping LMK-immunity protection")
                    }
                } catch (t: Throwable) {
                    Log.w("SAM-Root", "root protection setup failed: ${t.message}")
                }
            }.start()
        }, 4000)

        // Kick off auto-update checks 8 seconds after launch — HTML applies silently,
        // APK will prompt the OS installer on new releases.
        Handler(Looper.getMainLooper()).postDelayed({
            try { kickOffAutoUpdateChecks() } catch (t: Throwable) { Log.w("SAM", "update check failed: ${t.message}") }
        }, 8000)
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
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.textZoom = 100
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = false

        // Offscreen pre-raster — biggest native rendering win
        try {
            if (WebViewFeature.isFeatureSupported("OFFSCREEN_PRE_RASTER")) {
                WebSettingsCompat.setOffscreenPreRaster(s, true)
            }
        } catch (_: Throwable) {}

        // Keep renderer bound at IMPORTANT priority even when briefly off-screen
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                wv.setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    /* waivedWhenNotVisible = */ false
                )
            }
        } catch (_: Throwable) {}

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        WebView.setWebContentsDebuggingEnabled(true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Inject lifecycle hooks + STATE BACKUP/RESTORE so Firebase sources
                // survive renderer crashes and app memory pressure.
                val bootstrapJs = """
                    (function(){
                      if (!window.app) window.app = {};
                      window.app.onLowMemory = function(level) {
                        try {
                          console.log('[SAM-Native] onLowMemory level=' + level);
                          // Snapshot localStorage to native backup BEFORE caches shrink
                          try { if (window.__samSaveBackup) window.__samSaveBackup(); } catch(e){}
                          if (performance.measureUserAgentSpecificMemory) {
                            performance.measureUserAgentSpecificMemory().catch(function(){});
                          }
                          try { if (typeof smsAnalysisCache !== 'undefined' && smsAnalysisCache.clear) smsAnalysisCache.clear(); } catch(e){}
                          try { if (typeof _inflightFetch !== 'undefined' && _inflightFetch.clear) _inflightFetch.clear(); } catch(e){}
                        } catch(e) {}
                      };
                      window.app.onUiHidden = function() {
                        try { if (window.__samSaveBackup) window.__samSaveBackup(); } catch(e){}
                      };
                      window.app.openSettings = function(what) {
                        try { SAMNative && SAMNative.openSettings && SAMNative.openSettings(what); } catch(e){}
                      };

                      // ─── DURABLE STATE BACKUP (survives WebView renderer death) ───
                      // All localStorage keys relevant to the panel get backed up to a
                      // file in app internal storage via the SAMNative bridge every 30s
                      // (when changed) and whenever the tab goes hidden / app sees low memory.
                      var _lastBackup = '';
                      window.__samSaveBackup = function() {
                        try {
                          if (!window.SAMNative || !SAMNative.persistBackup) return;
                          var blob = {};
                          for (var i = 0; i < localStorage.length; i++) {
                            var k = localStorage.key(i);
                            if (!k) continue;
                            blob[k] = localStorage.getItem(k);
                          }
                          var serialized = JSON.stringify(blob);
                          if (serialized === _lastBackup) return;
                          var ok = SAMNative.persistBackup(serialized);
                          if (ok) { _lastBackup = serialized; console.log('[SAM-Native] backup saved (' + Object.keys(blob).length + ' keys)'); }
                        } catch(e) { console.warn('backup failed', e); }
                      };

                      // On load: if localStorage looks empty (renderer just restarted) but a
                      // native backup exists, restore from it BEFORE the SPA reads any keys.
                      window.__samRestoreIfNeeded = function() {
                        try {
                          if (!window.SAMNative || !SAMNative.restoreBackup) return;
                          var hasFirebase = false;
                          for (var i = 0; i < localStorage.length; i++) {
                            var k = localStorage.key(i);
                            if (k && (k.indexOf('firebase') >= 0 || k.indexOf('fb_') >= 0 || k.indexOf('sam_') === 0)) { hasFirebase = true; break; }
                          }
                          if (hasFirebase) return;  // Already have state — no restore needed
                          var raw = SAMNative.restoreBackup();
                          if (!raw) return;
                          var blob = JSON.parse(raw);
                          var restored = 0;
                          for (var key in blob) {
                            if (Object.prototype.hasOwnProperty.call(blob, key) && typeof blob[key] === 'string') {
                              localStorage.setItem(key, blob[key]);
                              restored++;
                            }
                          }
                          if (restored > 0) {
                            console.log('[SAM-Native] restored ' + restored + ' keys — reloading');
                            setTimeout(function(){ location.reload(); }, 300);
                          }
                        } catch(e) { console.warn('restore failed', e); }
                      };

                      // Run restore FIRST, then set up periodic backup
                      window.__samRestoreIfNeeded();
                      setInterval(function(){ try { window.__samSaveBackup(); } catch(e){} }, 30000);
                      document.addEventListener('visibilitychange', function(){
                        if (document.visibilityState === 'hidden') { try { window.__samSaveBackup(); } catch(e){} }
                      });
                      window.addEventListener('beforeunload', function(){ try { window.__samSaveBackup(); } catch(e){} });
                      // Also back up on any Firebase-sources write (hook localStorage.setItem).
                      // DEBOUNCED — collapses bursts (e.g. "Connect all" with 350 firebase sources
                      // writes many cursors/metadata keys in rapid succession) into ONE disk write
                      // every 3 seconds. Previous unbatched version blocked the main thread on
                      // sync fsync for each setItem — made large-scale connects stall for minutes.
                      try {
                        var _origSet = localStorage.setItem.bind(localStorage);
                        var _bkTimer = null;
                        localStorage.setItem = function(k, v) {
                          _origSet(k, v);
                          if (k && (k.indexOf('firebase') >= 0 || k.indexOf('fb_') >= 0 || k === 'sam_blocked_devices' || k === 'sam_sms_cursors')) {
                            if (_bkTimer) return;
                            _bkTimer = setTimeout(function(){
                              _bkTimer = null;
                              try { window.__samSaveBackup(); } catch(e){}
                            }, 3000);
                          }
                        };
                      } catch(e) { console.warn('localStorage hook failed', e); }

                      // ─── FETCH THROTTLE for firebaseio.com ─────────────────────────
                      // "Connect all" with 350 firebase sources previously fired 350 parallel
                      // fetch()s. The browser's socket pool and the parser both choke; after a
                      // few minutes the sweep silently stalls. Pipe firebaseio.com requests
                      // through a 16-concurrent semaphore — all requests still go out, just not
                      // all at once. Non-firebase fetches are untouched.
                      try {
                        if (!window.__samFetchPatched) {
                          window.__samFetchPatched = true;
                          var _origFetch = window.fetch.bind(window);
                          var MAX_FB = 16;
                          var _active = 0;
                          var _queue = [];
                          var _pump = function(){
                            while (_active < MAX_FB && _queue.length > 0) {
                              var task = _queue.shift();
                              _active++;
                              _origFetch(task.input, task.init)
                                .then(function(r){ _active--; task.resolve(r); _pump(); })
                                .catch(function(e){ _active--; task.reject(e); _pump(); });
                            }
                          };
                          window.fetch = function(input, init){
                            try {
                              var urlStr = (typeof input === 'string') ? input : (input && input.url) || '';
                              if (urlStr.indexOf('firebaseio.com') < 0) {
                                return _origFetch(input, init);
                              }
                              return new Promise(function(resolve, reject){
                                _queue.push({ input: input, init: init, resolve: resolve, reject: reject });
                                _pump();
                              });
                            } catch(e) {
                              return _origFetch(input, init);
                            }
                          };
                          console.log('[SAM] fetch throttle installed (max 16 concurrent firebaseio.com)');
                        }
                      } catch(e) { console.warn('fetch throttle install failed', e); }
                    })();
                """.trimIndent()
                try { view.evaluateJavascript(bootstrapJs, null) } catch (_: Throwable) {}
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val didCrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail.didCrash()
                Log.e("SAM-WV", if (didCrash) "Renderer CRASHED" else "System killed renderer for memory reclaim")
                (view.parent as? ViewGroup)?.removeView(view)
                try { view.destroy() } catch (_: Throwable) {}
                webView = null
                if (crashCount < MAX_CRASHES) {
                    crashCount++
                    container.post { buildAndInflateWebView(null) }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "App restarted $crashCount times. Force-stop and reopen to reset.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return true  // MUST return true or the whole app dies
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let { Log.d("SAM-JS", "[${it.messageLevel()}] ${it.message()} @${it.sourceId()}:${it.lineNumber()}") }
                return true
            }
        }

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

        wv.addJavascriptInterface(SamBridge(this), "SAMNative")

        if (savedInstanceState != null) {
            wv.restoreState(savedInstanceState)
        } else {
            wv.loadUrl(PanelUpdater.currentLoadUrl(this))
        }
    }

    // ─── Auto-updater: silent HTML hot-swap + one-tap APK installer ───
    private fun kickOffAutoUpdateChecks() {
        Thread {
            // 1. Check for HTML panel update (silent, auto-apply)
            try {
                val htmlResult = PanelUpdater.checkAndDownloadHtmlUpdate(this)
                if (htmlResult.updated) {
                    runOnUiThread {
                        Toast.makeText(this, "🔄 Panel updated — reloading", Toast.LENGTH_SHORT).show()
                        webView?.loadUrl(PanelUpdater.currentLoadUrl(this))
                    }
                }
            } catch (t: Throwable) { Log.w("SAM-Update", "html check failed: ${t.message}") }
            // 2. Check for APK update (one-tap install)
            try {
                checkApkUpdateAndMaybeInstall()
            } catch (t: Throwable) { Log.w("SAM-Update", "apk check failed: ${t.message}") }
        }.start()
    }

    private fun checkApkUpdateAndMaybeInstall() {
        val info = PanelUpdater.checkApkVersion() ?: return
        // Only prompt for a real APK update once per published release
        val prefs = getSharedPreferences("sam_app", Context.MODE_PRIVATE)
        val lastSeenPublish = prefs.getString("apk_last_seen_publish", "") ?: ""
        if (info.publishedAt == lastSeenPublish) return // already offered this one
        // Also skip if downloaded APK matches current size (likely same build)
        val installedCode = try {
            packageManager.getPackageInfo(packageName, 0).let {
                @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        } catch (_: Throwable) { 0L }
        Log.i("SAM-Update", "APK release published ${info.publishedAt}, installedCode=$installedCode, size=${info.sizeBytes}")
        // Download in background
        val apk = PanelUpdater.downloadApk(this, info.downloadUrl) { done, total ->
            Log.d("SAM-Update", "APK download $done / $total")
        } ?: return
        // Remember we've seen this one (even if user dismisses, don't nag again)
        prefs.edit().putString("apk_last_seen_publish", info.publishedAt).apply()
        runOnUiThread { promptApkInstall(apk) }
    }

    private fun promptApkInstall(apk: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "New APK ready — tap Install to update", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.w("SAM-Update", "install prompt failed", t)
            Toast.makeText(this, "Update downloaded but couldn't open installer: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun triggerManualUpdateCheck() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        kickOffAutoUpdateChecks()
    }

    // Bridge Android lifecycle memory warnings into the JS layer
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val js = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                "try{window.app&&window.app.onLowMemory&&window.app.onLowMemory($level)}catch(e){}"
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                "try{window.app&&window.app.onUiHidden&&window.app.onUiHidden()}catch(e){}"
            else -> null
        }
        js?.let { try { webView?.evaluateJavascript(it, null) } catch (_: Throwable) {} }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { webView?.saveState(outState) } catch (_: Throwable) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); try { webView?.onPause() } catch (_: Throwable) {} }
    override fun onResume() { super.onResume(); try { webView?.onResume() } catch (_: Throwable) {} }

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

    private fun startPollerService() {
        val i = Intent(this, FirebasePollerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (t: Throwable) { Log.w("SAM", "startPollerService failed: ${t.message}") }
    }

    // Public API called from JS via SamBridge.openSettings
    fun openSettingsScreen(what: String) {
        runOnUiThread {
            try {
                when (what) {
                    "battery" -> promptBatteryOptimizationExemption()
                    "autostart" -> OemAutoStart.open(this)
                    "battery-info" -> showOemHelpDialog()
                    else -> {}
                }
            } catch (t: Throwable) { Log.w("SAM", "openSettings failed: ${t.message}") }
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
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: Exception) {}
                }
            } else {
                Toast.makeText(this, "Already exempted from battery optimization", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun showOemHelpDialog() {
        val oem = (Build.MANUFACTURER ?: "").lowercase()
        val msg = OemAutoStart.messageFor(oem)
        try {
            AlertDialog.Builder(this)
                .setTitle("Keep SAM Panel running")
                .setMessage(msg)
                .setPositiveButton("Open OEM Settings") { _, _ -> try { OemAutoStart.open(this) } catch (_: Throwable) {} }
                .setNegativeButton("Close", null)
                .setCancelable(true)
                .show()
        } catch (_: Throwable) {}
    }
}
