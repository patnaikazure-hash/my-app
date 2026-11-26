package com.patnaik.sam

import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.lang.ref.WeakReference

class SamBridge(activity: MainActivity) {
    private val actRef = WeakReference(activity)
    private val ctx = activity.applicationContext

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

    // ─── Durable state backup — survives WebView renderer crashes ───
    // When Android kills the WebView renderer to reclaim memory, the localStorage
    // for that renderer can be lost before the async write-to-disk completes.
    // These methods write Firebase sources / settings / blocked devices to a plain
    // file in app internal storage (filesDir/state-backup.json), which Android
    // only clears on app uninstall or explicit cache clear.

    @JavascriptInterface
    fun persistBackup(jsonBlob: String?): Boolean {
        return try {
            if (jsonBlob.isNullOrEmpty()) return false
            val dir = File(ctx.filesDir, "state")
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "state-backup.json.tmp")
            tmp.writeText(jsonBlob)
            val dst = File(dir, "state-backup.json")
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
            true
        } catch (t: Throwable) {
            Log.w("SAM-Backup", "persistBackup failed: ${t.message}")
            false
        }
    }

    @JavascriptInterface
    fun restoreBackup(): String {
        return try {
            val f = File(File(ctx.filesDir, "state"), "state-backup.json")
            if (!f.exists()) "" else f.readText()
        } catch (t: Throwable) {
            Log.w("SAM-Backup", "restoreBackup failed: ${t.message}")
            ""
        }
    }
}
