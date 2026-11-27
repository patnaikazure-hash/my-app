package com.patnaik.sam

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
    // Sliding-window crash budget: 3 renderer crashes within 10 minutes before we give up.
    // System kills (didCrash=false) don't count — they're not our fault and state is intact.
    private var recentCrashes: MutableList<Long> = mutableListOf()
    private val MAX_CRASHES_PER_WINDOW = 3
    private val CRASH_WINDOW_MS = 600_000L

    private var lastExitReason: String = "none"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = findViewById(R.id.web_container)

        // Read why we died last time BEFORE loading the panel, so JS can start cold
        // in reduced-capacity mode if the previous death was memory-pressure related.
        lastExitReason = diagnoseLastExit()

        // Build WebView FIRST — no auto permission prompts, no auto service start.
        buildAndInflateWebView(savedInstanceState)

        // Defer all setup prompts by 5 seconds so the panel is visible first
        Handler(Looper.getMainLooper()).postDelayed({
            try { startPollerService() } catch (t: Throwable) { Log.w("SAM", "poller start failed: ${t.message}") }
        }, 5000)

        // ─── ROOT-ONLY WebView protection ───
        Handler(Looper.getMainLooper()).postDelayed({
            Thread {
                try {
                    if (RootProtection.isRooted()) {
                        val result = RootProtection.applyProtection(this)
                        Log.i("SAM-Root", result)
                        runOnUiThread {
                            Toast.makeText(this, "🛡️ Root protection active — renderers pinned, freezer off", Toast.LENGTH_SHORT).show()
                        }
                        // Re-apply every 30s so new WebView renderers also get pinned
                        RootProtection.startPeriodicReapply(this, 30_000)
                    } else {
                        Log.i("SAM-Root", "no root — skipping LMK-immunity protection")
                    }
                } catch (t: Throwable) {
                    Log.w("SAM-Root", "root protection setup failed: ${t.message}")
                }
            }.start()
        }, 4000)

        // Kick off auto-update checks 8 seconds after launch
        Handler(Looper.getMainLooper()).postDelayed({
            try { kickOffAutoUpdateChecks() } catch (t: Throwable) { Log.w("SAM", "update check failed: ${t.message}") }
        }, 8000)
    }

    /**
     * Read ApplicationExitInfo for our package. Returns one of:
     *   "anonswap"  — Android 17 per-app memory limiter killed us
     *   "renderoom" — renderer died of LOW_MEMORY or has "OOM" in description
     *   "crash"     — ordinary native crash
     *   "kill"      — SIGNALED
     *   "other"     — exit history exists but no match
     *   "none"      — no history (first run or pre-R)
     */
    private fun diagnoseLastExit(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "none"
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (list.isNullOrEmpty()) return "none"
            val summary = list.joinToString("|") { info ->
                "pid=${info.pid} reason=${info.reason} status=${info.status} desc=${info.description ?: ""}"
            }
            Log.w("SAM-Exit", summary)
            for (info in list) {
                val desc = info.description ?: ""
                if (desc.contains("MemoryLimiter:AnonSwap")) return "anonswap"
                if (info.reason == ApplicationExitInfo.REASON_LOW_MEMORY) return "renderoom"
                if (desc.contains("OOM", ignoreCase = true)) return "renderoom"
                if (info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return "crash"
                if (info.reason == ApplicationExitInfo.REASON_SIGNALED) return "kill"
            }
            "other"
        } catch (t: Throwable) {
            Log.w("SAM-Exit", "diagnoseLastExit failed: ${t.message}")
            "none"
        }
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

        try {
            if (WebViewFeature.isFeatureSupported("OFFSCREEN_PRE_RASTER")) {
                WebSettingsCompat.setOffscreenPreRaster(s, true)
            }
        } catch (_: Throwable) {}

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
                injectBootstrap(view)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val didCrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail.didCrash()
                val priAtExit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail.rendererPriorityAtExit() else -1
                Log.e("SAM-WV", "renderer gone didCrash=$didCrash prioAtExit=$priAtExit")

                (view.parent as? ViewGroup)?.removeView(view)
                try { view.destroy() } catch (_: Throwable) {}
                webView = null

                // Sliding window: 3 crashes per 10 min, else refuse to rebuild.
                // System kills don't count — they're not our fault and backup was written.
                val now = System.currentTimeMillis()
                recentCrashes.removeAll { now - it > CRASH_WINDOW_MS }
                if (didCrash) recentCrashes.add(now)

                if (recentCrashes.size <= MAX_CRASHES_PER_WINDOW) {
                    // Refresh the last-exit reason so the new session knows it just died
                    lastExitReason = diagnoseLastExit()
                    container.postDelayed({ buildAndInflateWebView(null) }, 400)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "App restarted ${recentCrashes.size} times. Force-stop and reopen to reset.",
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

    /**
     * Inject the full crash-prevention bootstrap: pressure canary, 5-level reaction
     * ladder, long-task observer, panic-save via SAMNative bridge, state backup,
     * telemetry, and startup diagnostic from last ApplicationExitInfo.
     */
    private fun injectBootstrap(view: WebView) {
        val bootstrapJs = """
            (function(){
              if (window.__samBootstrapInstalled) return;
              window.__samBootstrapInstalled = true;
              if (!window.app) window.app = {};

              // ─── 0. Startup diagnostic from last ApplicationExitInfo ──────────
              // Set BEFORE Firebase subscriptions fire so SPA can choose reduced-capacity mode.
              try {
                var lastExit = (window.SAMNative && SAMNative.getLastExitReason) ? SAMNative.getLastExitReason() : 'none';
                window.__lastExitReason = lastExit;
                if (lastExit === 'anonswap' || lastExit === 'renderoom') {
                  window.app.reducedCapacity = true;
                  console.warn('[SAM] previous session died of memory pressure (' + lastExit + ') — starting in reduced-capacity mode');
                }
                if (window.app.onStartupDiagnostic) {
                  try { window.app.onStartupDiagnostic(lastExit); } catch(e){}
                }
              } catch(e) { console.warn('startup diagnostic failed', e); }

              // ─── 1. Pressure canary — 5-level ladder, driven by performance.memory ──
              window.app.pressureLevel = 0;  // 0=OK 1=ELEVATED 2=HIGH 3=CRITICAL 4=PANIC
              var HEAP_LIMIT = (performance.memory && performance.memory.jsHeapSizeLimit) ||
                               ((navigator.deviceMemory || 2) * 256 * 1024 * 1024);
              window.app.heapLimit = HEAP_LIMIT;

              function samplePressure() {
                if (!performance.memory) return;
                var used = performance.memory.usedJSHeapSize;
                var r = used / HEAP_LIMIT;
                var next = r > 0.92 ? 4 : r > 0.85 ? 3 : r > 0.75 ? 2 : r > 0.60 ? 1 : 0;
                if (next !== window.app.pressureLevel) {
                  var from = window.app.pressureLevel;
                  window.app.pressureLevel = next;
                  try { onPressureChange(from, next, r, used); } catch(e){}
                }
              }
              setInterval(samplePressure, 1500);

              // ─── 2. Long-task observer — GC-thrash proxy ──────────────────
              var _longInLast10s = [];
              try {
                new PerformanceObserver(function(list){
                  var now = performance.now();
                  var entries = list.getEntries();
                  for (var i = 0; i < entries.length; i++) {
                    if (entries[i].duration >= 50) _longInLast10s.push(now);
                  }
                  _longInLast10s = _longInLast10s.filter(function(t){ return now - t < 10000; });
                  if (_longInLast10s.length > 20 && window.app.pressureLevel < 2) {
                    var from = window.app.pressureLevel;
                    window.app.pressureLevel = 2;
                    try { onPressureChange(from, 2, null, null); } catch(e){}
                  }
                }).observe({ type: 'longtask', buffered: true });
              } catch(e) {}

              // ─── 3. Reaction ladder ──────────────────────────────────────
              function onPressureChange(from, to, ratio, used) {
                try {
                  var msg = 'pressure ' + from + '->' + to;
                  if (ratio != null) msg += ' r=' + ratio.toFixed(3);
                  if (used != null) msg += ' used=' + used;
                  console.warn('[SAM] ' + msg);
                  if (window.SAMNative && SAMNative.log) SAMNative.log(msg);
                } catch(e){}
                if (to >= 1) clearCachesLevel1();
                if (to >= 2) clearCachesLevel2();
                if (to >= 3) panicSaveIncremental();
                if (to >= 4) panicSaveNow();
                if (to >= 3) tryInduceGC();
              }

              function clearCachesLevel1() {
                try { if (typeof _inflightFetch !== 'undefined' && _inflightFetch.clear) _inflightFetch.clear(); } catch(e){}
                try { if (typeof smsAnalysisCache !== 'undefined' && smsAnalysisCache.clear) smsAnalysisCache.clear(); } catch(e){}
              }
              function clearCachesLevel2() {
                clearCachesLevel1();
                try { if (typeof deviceCache !== 'undefined' && deviceCache.clear) deviceCache.clear(); } catch(e){}
                // Bump any image caches we can reach
                try {
                  var imgs = document.querySelectorAll('img[data-blob-cache]');
                  for (var i = 0; i < imgs.length; i++) { try { imgs[i].src = ''; } catch(e){} }
                } catch(e){}
              }

              // ─── 4. Panic save — writes localStorage snapshot via bridge ──
              var _lastBackup = '';
              var _backupInProgress = false;
              window.__samSaveBackup = function() {
                if (_backupInProgress) return false;
                _backupInProgress = true;
                try {
                  if (!window.SAMNative || !SAMNative.persistBackup) return false;
                  var blob = {};
                  for (var i = 0; i < localStorage.length; i++) {
                    var k = localStorage.key(i);
                    if (!k) continue;
                    blob[k] = localStorage.getItem(k);
                  }
                  var serialized = JSON.stringify(blob);
                  if (serialized === _lastBackup) return true;
                  var ok = SAMNative.persistBackup(serialized);
                  if (ok) {
                    _lastBackup = serialized;
                    console.log('[SAM] backup saved (' + Object.keys(blob).length + ' keys, ' + serialized.length + 'b)');
                  }
                  return ok;
                } catch(e) {
                  console.warn('backup failed', e);
                  return false;
                } finally {
                  _backupInProgress = false;
                }
              };
              window.app.panicSaveNow = function() {
                try { window.__samSaveBackup(); } catch(e){}
              };
              window.app.panicSaveIncremental = function() {
                // Debounced version — don't thrash disk if pressure oscillates
                if (window.__samIncTimer) return;
                window.__samIncTimer = setTimeout(function(){
                  window.__samIncTimer = null;
                  try { window.__samSaveBackup(); } catch(e){}
                }, 200);
              };
              window.app.onUiHidden = function() {
                try { window.__samSaveBackup(); } catch(e){}
              };
              window.app.onLowMemory = function(level) {
                try { console.warn('[SAM-Native] onLowMemory level=' + level); } catch(e){}
                try { window.__samSaveBackup(); } catch(e){}
                clearCachesLevel2();
              };

              // ─── 5. Soft-GC poke — folklore but nudges incremental marker ─
              function tryInduceGC() {
                try {
                  for (var i = 0; i < 4; i++) {
                    var x = new ArrayBuffer(8 * 1024 * 1024);
                    x = null;
                  }
                } catch(e) {
                  // Allocation itself failing IS the crash-imminent signal
                  try { window.__samSaveBackup(); } catch(e2){}
                }
              }

              // ─── 6. Visibility change — last reliable Android lifecycle event ─
              document.addEventListener('visibilitychange', function(){
                if (document.visibilityState === 'hidden') {
                  try { window.__samSaveBackup(); } catch(e){}
                }
              });
              window.addEventListener('beforeunload', function(){
                try { window.__samSaveBackup(); } catch(e){}
              });
              window.addEventListener('pagehide', function(){
                try { window.__samSaveBackup(); } catch(e){}
              });

              // ─── 7. Settings bridge ──────────────────────────────────────
              window.app.openSettings = function(what) {
                try { SAMNative && SAMNative.openSettings && SAMNative.openSettings(what); } catch(e){}
              };

              // ─── 8. Restore from backup on cold start ────────────────────
              window.__samRestoreIfNeeded = function() {
                try {
                  if (!window.SAMNative || !SAMNative.restoreBackup) return;
                  var hasFirebase = false;
                  for (var i = 0; i < localStorage.length; i++) {
                    var k = localStorage.key(i);
                    if (k && (k.indexOf('firebase') >= 0 || k.indexOf('fb_') >= 0 || k.indexOf('sam_') === 0)) {
                      hasFirebase = true;
                      break;
                    }
                  }
                  if (hasFirebase) return;
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
                    console.log('[SAM] restored ' + restored + ' keys — reloading');
                    setTimeout(function(){ location.reload(); }, 300);
                  }
                } catch(e) { console.warn('restore failed', e); }
              };
              window.__samRestoreIfNeeded();

              // ─── 9. Periodic backup (30s) + hook localStorage.setItem ────
              setInterval(function(){ try { window.__samSaveBackup(); } catch(e){} }, 30000);
              try {
                var _origSet = localStorage.setItem.bind(localStorage);
                localStorage.setItem = function(k, v) {
                  _origSet(k, v);
                  if (k && (k.indexOf('firebase') >= 0 || k.indexOf('fb_') >= 0 ||
                            k === 'sam_blocked_devices' || k === 'sam_sms_cursors')) {
                    // Debounce — don't write on every character change
                    if (window.__samSetTimer) clearTimeout(window.__samSetTimer);
                    window.__samSetTimer = setTimeout(function(){
                      try { window.__samSaveBackup(); } catch(e){}
                    }, 500);
                  }
                };
              } catch(e) { console.warn('localStorage hook failed', e); }

              // ─── 10. Heap telemetry — persists one sample per 30s to filesDir ─
              setInterval(function(){
                try {
                  if (!performance.memory || !window.SAMNative || !SAMNative.recordHeapSample) return;
                  var m = {
                    ts: Date.now(),
                    used: performance.memory.usedJSHeapSize,
                    total: performance.memory.totalJSHeapSize,
                    limit: performance.memory.jsHeapSizeLimit,
                    ratio: performance.memory.usedJSHeapSize / performance.memory.jsHeapSizeLimit,
                    lsKeys: localStorage.length,
                    pressure: window.app.pressureLevel,
                    longs10s: _longInLast10s.length
                  };
                  SAMNative.recordHeapSample(JSON.stringify(m));
                } catch(e){}
              }, 30000);

              console.log('[SAM] bootstrap installed — heap limit ' + HEAP_LIMIT + ', last exit: ' + (window.__lastExitReason || 'none'));
            })();
        """.trimIndent()
        try { view.evaluateJavascript(bootstrapJs, null) } catch (_: Throwable) {}
    }

    // ─── Auto-updater: silent HTML hot-swap + one-tap APK installer ───
    private fun kickOffAutoUpdateChecks() {
        Thread {
            try {
                val htmlResult = PanelUpdater.checkAndDownloadHtmlUpdate(this)
                if (htmlResult.updated) {
                    runOnUiThread {
                        Toast.makeText(this, "🔄 Panel updated — reloading", Toast.LENGTH_SHORT).show()
                        webView?.loadUrl(PanelUpdater.currentLoadUrl(this))
                    }
                }
            } catch (t: Throwable) { Log.w("SAM-Update", "html check failed: ${t.message}") }
            try {
                checkApkUpdateAndMaybeInstall()
            } catch (t: Throwable) { Log.w("SAM-Update", "apk check failed: ${t.message}") }
        }.start()
    }

    private fun checkApkUpdateAndMaybeInstall() {
        val info = PanelUpdater.checkApkVersion() ?: return
        val prefs = getSharedPreferences("sam_app", Context.MODE_PRIVATE)
        val lastSeenPublish = prefs.getString("apk_last_seen_publish", "") ?: ""
        if (info.publishedAt == lastSeenPublish) return
        val installedCode = try {
            packageManager.getPackageInfo(packageName, 0).let {
                @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        } catch (_: Throwable) { 0L }
        Log.i("SAM-Update", "APK release published ${info.publishedAt}, installedCode=$installedCode, size=${info.sizeBytes}")
        val apk = PanelUpdater.downloadApk(this, info.downloadUrl) { done, total ->
            Log.d("SAM-Update", "APK download $done / $total")
        } ?: return
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

    // Bridge Android lifecycle memory warnings into the JS pressure ladder.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val js = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                "try{window.app&&window.app.panicSaveNow&&window.app.panicSaveNow();if(window.app)window.app.pressureLevel=4}catch(e){}"
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                "try{window.app&&window.app.panicSaveIncremental&&window.app.panicSaveIncremental();if(window.app)window.app.pressureLevel=Math.max(window.app.pressureLevel||0,3)}catch(e){}"
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
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
