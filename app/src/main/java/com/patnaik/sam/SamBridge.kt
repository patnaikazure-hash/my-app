package com.patnaik.sam

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Minimal JS <-> Kotlin bridge. Exposed to JS as window.SAMNative.
 * KEEP IT TINY — @JavascriptInterface calls are synchronous and can block the renderer
 * if they do real work. Only fire-and-forget methods here.
 */
class SamBridge {
    @JavascriptInterface
    fun log(msg: String) {
        Log.i("SAM-JS", msg)
    }

    @JavascriptInterface
    fun markAlive(ts: Long) {
        // Heartbeat hook — could be wired to a watchdog in future
    }
}
