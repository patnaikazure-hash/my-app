package com.patnaik.sam

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView

class SamApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Multi-process safety: if any secondary process ever uses WebView, it MUST set a distinct
        // data-directory suffix or Chromium throws "WebView already initialized".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cur = currentProcessName()
            if (cur != null && cur != packageName) {
                try {
                    WebView.setDataDirectorySuffix(cur.replace(":", "_"))
                } catch (t: Throwable) {
                    Log.w("SAM", "setDataDirectorySuffix failed: ${t.message}")
                }
            }
        }

        // NOTE: WebViewCompat.startUpWebView requires androidx.webkit 1.16.0+ (not stable yet in
        // the 1.11.0 series we pin). Skipping pre-warm — the WebView will initialize on first use.
        // When androidx.webkit 1.16.0 or newer becomes available we can re-enable pre-warm here.
    }

    private fun currentProcessName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else null
    }
}
