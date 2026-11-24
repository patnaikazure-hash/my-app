package com.patnaik.sam

import android.util.Log
import android.webkit.JavascriptInterface
import java.lang.ref.WeakReference

class SamBridge(activity: MainActivity) {
    private val actRef = WeakReference(activity)

    @JavascriptInterface
    fun log(msg: String) { Log.i("SAM-JS", msg) }

    @JavascriptInterface
    fun markAlive(ts: Long) {}

    // JS can call: SAMNative.openSettings("battery"|"autostart"|"battery-info")
    @JavascriptInterface
    fun openSettings(what: String) {
        actRef.get()?.openSettingsScreen(what ?: "battery")
    }

    // JS can call: SAMNative.checkForUpdates() to manually fetch latest panel + APK
    @JavascriptInterface
    fun checkForUpdates() {
        actRef.get()?.runOnUiThread {
            actRef.get()?.triggerManualUpdateCheck()
        }
    }
}
