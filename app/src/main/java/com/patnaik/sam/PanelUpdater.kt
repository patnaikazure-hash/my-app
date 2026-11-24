package com.patnaik.sam

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Handles in-app updates for both the panel HTML and the APK itself.
 *
 * HTML updates: downloaded from GitHub raw URL, saved to filesDir/panel/index.html,
 * loaded by MainActivity on next WebView load. Diffs by SHA-256; only applies when
 * content actually changed.
 *
 * APK updates: downloaded to filesDir/updates/, then install prompt via FileProvider.
 */
object PanelUpdater {

    private const val TAG = "SAM-Update"
    private const val HTML_URL =
        "https://raw.githubusercontent.com/patnaikazure-hash/my-app/sam-android/app/src/main/assets/index.html"
    private const val RELEASE_API =
        "https://api.github.com/repos/patnaikazure-hash/my-app/releases/tags/sam-android-latest"
    private const val APK_URL =
        "https://github.com/patnaikazure-hash/my-app/releases/download/sam-android-latest/app-debug.apk"

    // ─── HTML path management ─────────────────────────────────────

    fun cachedHtmlFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "panel")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "index.html")
    }

    fun cachedApkFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "panel-update.apk")
    }

    /**
     * Returns the URL to load in WebView — cached updated HTML if present, else asset.
     */
    fun currentLoadUrl(ctx: Context): String {
        val cached = cachedHtmlFile(ctx)
        return if (cached.exists() && cached.length() > 1000) {
            "file://${cached.absolutePath}"
        } else {
            "file:///android_asset/index.html"
        }
    }

    // ─── HTML update check (safe to call on background thread) ─────────

    /**
     * Downloads the latest index.html and compares SHA-256 with the current cached (or asset) one.
     * If newer, saves to cache and returns true. Otherwise returns false.
     * Caller must be on a background thread — makes blocking network calls.
     */
    fun checkAndDownloadHtmlUpdate(ctx: Context): Result {
        return try {
            val conn = URL(HTML_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "text/html")
            conn.setRequestProperty("User-Agent", "SAMPanel-Android/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != 200) {
                return Result(false, "HTTP $code", null)
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 10_000) {
                return Result(false, "Downloaded file too small (${bytes.size} bytes)", null)
            }
            val newHash = sha256(bytes)
            // Compare with current loaded content
            val current = cachedHtmlFile(ctx)
            val currentBytes = if (current.exists()) current.readBytes()
                               else ctx.assets.open("index.html").use { it.readBytes() }
            val currentHash = sha256(currentBytes)
            if (newHash == currentHash) {
                return Result(false, "Already up to date", null)
            }
            // Write to a temp file then rename atomically
            val tmp = File(current.parentFile, "index.html.tmp")
            tmp.writeBytes(bytes)
            if (current.exists()) current.delete()
            tmp.renameTo(current)
            Log.i(TAG, "HTML update applied: ${bytes.size} bytes, hash ${newHash.take(8)}")
            Result(true, "Panel updated (${(bytes.size / 1024)}KB) — reload to apply", newHash)
        } catch (t: Throwable) {
            Log.w(TAG, "HTML update check failed", t)
            Result(false, "Check failed: ${t.message}", null)
        }
    }

    // ─── APK update check ──────────────────────────────────────────────

    /**
     * Checks GitHub Releases API for the latest APK version. Returns the versionName if newer
     * than the installed one, else null.
     */
    fun checkApkVersion(): ApkInfo? {
        return try {
            val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SAMPanel-Android/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Release API HTTP $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Minimal JSON parse — extract "published_at" + first asset size + download url
            val published = Regex("\"published_at\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val assetSize = Regex("\"size\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val downloadUrl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(body)?.groupValues?.get(1)
                ?: APK_URL
            ApkInfo(publishedAt = published ?: "", downloadUrl = downloadUrl, sizeBytes = assetSize)
        } catch (t: Throwable) {
            Log.w(TAG, "APK version check failed", t)
            null
        }
    }

    /**
     * Downloads the APK to filesDir/updates/panel-update.apk. Returns the File on success.
     * Blocking — call on background thread. onProgress is called with bytes-downloaded periodically.
     */
    fun downloadApk(ctx: Context, url: String, onProgress: ((Long, Long) -> Unit)? = null): File? {
        return try {
            val target = cachedApkFile(ctx)
            if (target.exists()) target.delete()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 120000
            conn.setRequestProperty("User-Agent", "SAMPanel-Android/${BuildConfig.VERSION_NAME}")
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastReport = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (downloaded - lastReport > 256 * 1024) {
                            lastReport = downloaded
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                    onProgress?.invoke(downloaded, total)
                }
            }
            if (target.length() < 100_000) {
                Log.w(TAG, "APK download too small: ${target.length()} bytes")
                return null
            }
            Log.i(TAG, "APK downloaded: ${target.length()} bytes -> ${target.absolutePath}")
            target
        } catch (t: Throwable) {
            Log.w(TAG, "APK download failed", t)
            null
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    data class Result(val updated: Boolean, val message: String, val sha256: String?)
    data class ApkInfo(val publishedAt: String, val downloadUrl: String, val sizeBytes: Long)
}
