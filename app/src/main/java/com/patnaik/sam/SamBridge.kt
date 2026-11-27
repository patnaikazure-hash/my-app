package com.patnaik.sam

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Bridge between the WebView's JavaScript panel and the Kotlin host.
 *
 * Every @JavascriptInterface method is SYNCHRONOUS — the JS caller blocks until the
 * Kotlin side returns. This is the only sync escape hatch from the JS main thread
 * and is used for the panic-save path: when memory pressure spikes, JS can hand
 * state to disk without waiting for an IndexedDB transaction (whose onsuccess is
 * not guaranteed to fire during lifecycle handlers).
 *
 * Writes go to the app's internal filesDir (never cleared except on uninstall) under:
 *   state/state-backup.json       — full-store blob backup (legacy)
 *   snapshots/<deviceId>.json     — per-device fast path
 *   dirty/<key>_<index>.part      — streamed chunks for huge blobs
 */
class SamBridge(activity: MainActivity) {
    private val actRef = WeakReference(activity)
    private val ctx = activity.applicationContext

    private val stateDir = File(ctx.filesDir, "state").apply { mkdirs() }
    private val snapshotDir = File(ctx.filesDir, "snapshots").apply { mkdirs() }
    private val dirtyDir = File(ctx.filesDir, "dirty").apply { mkdirs() }

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

    // ─────────────────────────────────────────────────────────────────────
    // Durable state backup — legacy whole-store blob (kept for restore path)
    // ─────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun persistBackup(jsonBlob: String?): Boolean {
        return try {
            if (jsonBlob.isNullOrEmpty()) return false
            val tmp = File(stateDir, "state-backup.json.tmp")
            tmp.writeText(jsonBlob)
            val dst = File(stateDir, "state-backup.json")
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
            val f = File(stateDir, "state-backup.json")
            if (!f.exists()) "" else f.readText()
        } catch (t: Throwable) {
            Log.w("SAM-Backup", "restoreBackup failed: ${t.message}")
            ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fast per-device sync save — called from the JS pressure ladder's
    // panic-save path. Writes atomically with fsync. Returns false if the
    // disk is full or we can't write — JS stops iterating on first false.
    // ─────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun saveDeviceSync(deviceId: String?, json: String?): Boolean {
        if (deviceId.isNullOrEmpty() || json == null) return false
        val safe = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val tmp = File(dirtyDir, "$safe.tmp")
        val dst = File(snapshotDir, "$safe.json")
        return try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            tmp.renameTo(dst)
        } catch (t: Throwable) {
            Log.w("SAM-Bridge", "saveDeviceSync $deviceId failed: ${t.message}")
            false
        }
    }

    /**
     * Stream a large blob across many sync calls instead of one giant one — safer for
     * 50MB+ payloads where a single JS-side JSON.stringify would double the heap.
     */
    @JavascriptInterface
    fun savePartialSync(key: String?, index: Int, total: Int, chunk: String?): Boolean {
        if (key.isNullOrEmpty() || chunk == null) return false
        val safeKey = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dst = File(dirtyDir, "${safeKey}_${index.toString().padStart(4, '0')}.part")
        return try {
            FileOutputStream(dst).use {
                it.write(chunk.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            true
        } catch (t: Throwable) { false }
    }

    /** Read a per-device snapshot written by saveDeviceSync. Returns "" if not found. */
    @JavascriptInterface
    fun loadDeviceSync(deviceId: String?): String {
        if (deviceId.isNullOrEmpty()) return ""
        val safe = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val f = File(snapshotDir, "$safe.json")
        return try { if (f.exists()) f.readText() else "" } catch (_: Throwable) { "" }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Exit-reason diagnostic — reads ApplicationExitInfo so the SPA can
    // choose reduced-capacity startup when the previous death was an
    // Android 17 per-app memory-limit kill (MemoryLimiter:AnonSwap) or
    // a renderer OOM.  Returns one of: "anonswap" | "renderoom" | "crash"
    // | "kill" | "none".
    // ─────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getLastExitReason(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "none"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            if (list.isNullOrEmpty()) return "none"
            // Check most recent first.
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
            Log.w("SAM-Exit", "getLastExitReason failed: ${t.message}")
            "none"
        }
    }

    /**
     * Returns a JSON array of the last 5 exits (reason, status, desc) for debugging.
     * JS can display this to the user when they tap "show diagnostics".
     */
    @JavascriptInterface
    fun getExitHistory(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "[]"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) ?: return "[]"
            val sb = StringBuilder("[")
            list.forEachIndexed { i, info ->
                if (i > 0) sb.append(",")
                sb.append("{")
                sb.append("\"reason\":").append(info.reason)
                sb.append(",\"status\":").append(info.status)
                sb.append(",\"ts\":").append(info.timestamp)
                sb.append(",\"desc\":\"").append((info.description ?: "").replace("\"", "'")).append("\"")
                sb.append("}")
            }
            sb.append("]").toString()
        } catch (_: Throwable) { "[]" }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Telemetry — JS can push one heap sample per 30s so we build a
    // ring-buffered record of pre-crash heap trajectory.  Writes to
    // filesDir/telemetry/heap-<minute>.json.
    // ─────────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun recordHeapSample(json: String?): Boolean {
        if (json.isNullOrEmpty()) return false
        return try {
            val dir = File(ctx.filesDir, "telemetry").apply { mkdirs() }
            // Rotate by minute-of-hour so the last hour's samples all land in 60 files max.
            val slot = (System.currentTimeMillis() / 60_000L % 60L).toString().padStart(2, '0')
            val f = File(dir, "heap-$slot.json")
            f.writeText(json)
            true
        } catch (_: Throwable) { false }
    }

    /**
     * Returns the last N heap samples concatenated as a JSON array so the SPA
     * can draw a mini-chart of recent pressure. Used only when the user opens
     * the diagnostics screen; no cost at runtime.
     */
    @JavascriptInterface
    fun getHeapHistory(): String {
        return try {
            val dir = File(ctx.filesDir, "telemetry")
            if (!dir.exists()) return "[]"
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return "[]"
            val sb = StringBuilder("[")
            var first = true
            for (f in files) {
                try {
                    val body = f.readText().trim()
                    if (body.isEmpty()) continue
                    if (!first) sb.append(",")
                    sb.append(body)
                    first = false
                } catch (_: Throwable) {}
            }
            sb.append("]").toString()
        } catch (_: Throwable) { "[]" }
    }

    /**
     * Returns the Chromium renderer PSS for the current app's :sandboxed_process children
     * as a JSON array like [{"pid":12345,"totalPss":215040,"nativePss":187456}, ...].
     * JS can compare this to performance.memory to see whether total renderer RSS
     * is growing while V8 heap stays flat (i.e., DOM/PartitionAlloc leak, not V8 OOM).
     */
    @JavascriptInterface
    fun dumpRendererMeminfo(): String {
        return try {
            val pids = Runtime.getRuntime()
                .exec(arrayOf("sh", "-c", "pgrep -f sandboxed_process 2>/dev/null"))
                .inputStream.bufferedReader().readText().trim()
                .split("\n").filter { it.isNotBlank() }
            if (pids.isEmpty()) return "[]"
            val sb = StringBuilder("[")
            var first = true
            for (pidStr in pids) {
                val pid = pidStr.toIntOrNull() ?: continue
                try {
                    val mem = Runtime.getRuntime()
                        .exec(arrayOf("dumpsys", "meminfo", pid.toString()))
                        .inputStream.bufferedReader().readText()
                    var totalPss = 0
                    var nativePss = 0
                    mem.lineSequence().forEach { ln ->
                        val t = ln.trim()
                        if (t.startsWith("TOTAL PSS:")) {
                            totalPss = Regex("\\d+").findAll(t).firstOrNull()?.value?.toIntOrNull() ?: 0
                        } else if (t.startsWith("Native Heap")) {
                            nativePss = Regex("\\d+").findAll(t).firstOrNull()?.value?.toIntOrNull() ?: 0
                        }
                    }
                    if (!first) sb.append(",")
                    sb.append("{\"pid\":").append(pid)
                      .append(",\"totalPss\":").append(totalPss)
                      .append(",\"nativePss\":").append(nativePss).append("}")
                    first = false
                } catch (_: Throwable) {}
            }
            sb.append("]").toString()
        } catch (_: Throwable) { "[]" }
    }
}
