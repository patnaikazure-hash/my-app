package com.patnaik.sam

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root-only protection. Keeps the main app AND every WebView renderer sandbox PID
 * alive against the full stack of Android kill paths — LMKD, cached-apps freezer,
 * per-app memory limiter, OEM battery daemons.
 *
 * Why the old "echo -1000 > /proc/<app_pid>/oom_score_adj every 60s" loop was weak:
 *   1. ActivityManagerService's OomAdjuster recomputes adj from procstate on every
 *      binding/lifecycle change — written values are overwritten in seconds.
 *   2. It only hit the parent app PID. Renderers are children of webview_zygote,
 *      not the app, so pgrep -P <app_pid> misses them and the child's adj was never pinned.
 *   3. On Android 14+ the cached-apps freezer (cgroup v2 at /sys/fs/cgroup/uid_<UID>/
 *      cgroup.freeze) triggers 10s after CACHED procstate — oom_score_adj does NOT
 *      exempt a process from it, and a sync binder call to a frozen target kills it.
 *
 * What this does instead:
 *   - Enumerate renderers via `pgrep -f sandboxed_process` (correct for WebView)
 *   - Pin each renderer PID to oom_score_adj=-1000 AND cpuset top-app AND task_profiles MaxPerformance
 *   - Disable the cached-apps freezer system-wide via `device_config put activity_manager use_freezer false`
 *   - Add package to deviceidle whitelist (persistent through Doze)
 *   - Allow RUN_ANY_IN_BACKGROUND
 *   - Re-apply every N seconds as backstop for new renderer respawns
 *
 * The persistent part (sysconfig XML, boot-time service.sh) lives in the Magisk module
 * shipped separately; this class handles the per-session reinforcement.
 */
object RootProtection {

    private const val TAG = "SAM-Root"

    /** Fast root detection — su binary present AND executable, returning uid=0. */
    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sbin/su", "/vendor/bin/su", "/system/app/Superuser.apk",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su"
        )
        var haveBinary = false
        for (p in paths) if (File(p).exists()) { haveBinary = true; break }
        if (!haveBinary) return false
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readLine() ?: ""
            proc.waitFor()
            out.contains("uid=0")
        } catch (_: Throwable) { false }
    }

    /** Apply the full protection stack. Idempotent — safe to call repeatedly. */
    fun applyProtection(ctx: Context): String {
        val pkg = ctx.packageName
        val myPid = Process.myPid()
        val results = StringBuilder()
        val commands = mutableListOf<String>()

        // ─── 1. Find every PID related to our app ───────────────────────
        val appPids = findAppPids(pkg, myPid)
        val renderPids = findRendererPids()
        val allPids = (appPids + renderPids).distinct()
        results.append("app_pids=").append(appPids.joinToString(","))
            .append(" render_pids=").append(renderPids.joinToString(","))
            .append("\n")

        // ─── 2. Pin oom_score_adj=-1000 on every PID ────────────────────
        // Note: AMS re-overwrites the app PID within seconds of lifecycle changes,
        // but the renderer PIDs tend to hold because AMS re-evaluates them less often.
        for (pid in allPids) {
            commands += "echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null"
            // Pin to foreground cpuset via task_profiles (A12+) with manual fallback.
            commands += "/system/bin/task_profiles MaxPerformance $pid 2>/dev/null || true"
            commands += "/system/bin/task_profiles ProcessCapacityHigh $pid 2>/dev/null || true"
            commands += "echo $pid > /dev/cpuset/top-app/tasks 2>/dev/null || true"
            commands += "echo $pid > /dev/stune/top-app/tasks 2>/dev/null || true"
        }

        // ─── 3. Disable Android 14+ cached-apps freezer system-wide ────
        // This is what actually kills bound background apps on modern devices.
        // Re-applied every cycle because device_config overlay can be reset.
        commands += "device_config put activity_manager use_freezer false 2>/dev/null"
        commands += "device_config put activity_manager use_compaction false 2>/dev/null"

        // ─── 4. Persistent Doze + background allowlist (framework-level) ─
        commands += "cmd deviceidle whitelist +$pkg 2>/dev/null"
        commands += "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null"
        commands += "cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null"
        commands += "dumpsys deviceidle whitelist +$pkg 2>/dev/null"

        // ─── 5. Attempt per-UID freezer unfreeze if a renderer is frozen ─
        // A frozen renderer dies on next sync binder call; forcibly unfreeze.
        try {
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            commands += "echo 0 > /sys/fs/cgroup/uid_$uid/cgroup.freeze 2>/dev/null || true"
            // Unfreeze each renderer subcgroup too
            for (pid in renderPids) {
                commands += "echo 0 > /sys/fs/cgroup/uid_$uid/pid_$pid/cgroup.freeze 2>/dev/null || true"
            }
        } catch (_: Throwable) {}

        // ─── 6. Battery-saver allowlist via settings (persistent) ──────
        commands += "settings put global app_standby_enabled 0 2>/dev/null || true"

        val ok = runAsRoot(commands)
        results.append(if (ok) "✓ ok" else "✗ some cmd failed").append("\n")

        // Verify by reading back one renderer adj
        try {
            if (renderPids.isNotEmpty()) {
                val adj = File("/proc/${renderPids.first()}/oom_score_adj").readText().trim()
                results.append("render[0]=${renderPids.first()} adj=$adj")
            } else {
                val adj = File("/proc/$myPid/oom_score_adj").readText().trim()
                results.append("app adj=$adj")
            }
        } catch (t: Throwable) {
            results.append("verify-read failed: ${t.message}")
        }
        Log.i(TAG, results.toString())
        return results.toString()
    }

    /** Walk /proc looking for /cmdline that starts with our package name. */
    private fun findAppPids(pkg: String, mainPid: Int): List<Int> {
        val pids = mutableSetOf(mainPid)
        try {
            val procDir = File("/proc")
            val entries = procDir.listFiles() ?: return pids.toList()
            for (child in entries) {
                val name = child.name
                if (!name.all { it.isDigit() }) continue
                val pid = name.toIntOrNull() ?: continue
                try {
                    val cmd = File(child, "cmdline").readText().trim('\u0000').split('\u0000').first()
                    if (cmd.startsWith(pkg)) pids.add(pid)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return pids.toList()
    }

    /**
     * Enumerate WebView renderer sandbox PIDs via `pgrep -f sandboxed_process` as root.
     * Non-root pgrep usually can't see them because they run under isolated UIDs.
     */
    private fun findRendererPids(): List<Int> {
        return try {
            // Must run through su — otherwise pgrep can't inspect other-UID /proc/*/cmdline.
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pgrep -f sandboxed_process"))
            val text = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            text.trim().split("\n")
                .mapNotNull { it.trim().toIntOrNull() }
                .distinct()
        } catch (_: Throwable) { emptyList() }
    }

    /** Pipe commands to `su` in one invocation. */
    private fun runAsRoot(commands: List<String>): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val dos = DataOutputStream(proc.outputStream)
            for (cmd in commands) {
                dos.writeBytes(cmd)
                dos.writeBytes("\n")
            }
            dos.writeBytes("exit\n")
            dos.flush()
            dos.close()
            proc.waitFor() == 0
        } catch (t: Throwable) {
            Log.w(TAG, "su exec failed: ${t.message}")
            false
        }
    }

    /** Re-apply periodically because renderer respawns and AMS resets. */
    fun startPeriodicReapply(ctx: Context, intervalMs: Long = 30_000) {
        Thread {
            while (true) {
                try { applyProtection(ctx) } catch (_: Throwable) {}
                try { Thread.sleep(intervalMs) } catch (_: Throwable) { break }
            }
        }.apply { isDaemon = true; name = "SAM-RootProtection"; start() }
    }
}
