package com.patnaik.sam

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root-only protection: makes Android's Low Memory Killer physically unable to kill
 * the app's main process OR its WebView renderer child processes.
 *
 * The LMK scans /proc/<pid>/oom_score_adj — pids with a value of -1000 are IMMUNE.
 * Without root, only system processes get that value. With root, we can set it
 * for our own package, which means the WebView renderer never dies no matter how
 * much memory pressure Android sees.
 *
 * Also:
 *  - Adds the package to the Doze whitelist (dumpsys deviceidle whitelist +pkg)
 *  - Allows RUN_IN_BACKGROUND (cmd appops)
 *  - Pins the renderer thread priority via renice
 */
object RootProtection {

    private const val TAG = "SAM-Root"

    /** Fast check: does `su` exist and respond? */
    fun isRooted(): Boolean {
        // 1. Check for the su binary on the PATH / common locations
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sbin/su", "/vendor/bin/su", "/system/app/Superuser.apk",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su"
        )
        for (p in paths) {
            if (File(p).exists()) return true
        }
        // 2. Try executing `id` under su — if it returns uid=0 we're root
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readLine() ?: ""
            proc.waitFor()
            out.contains("uid=0")
        } catch (_: Throwable) { false }
    }

    /**
     * Apply full root protection. Returns a human-readable summary.
     * Safe to call repeatedly — all commands are idempotent.
     */
    fun applyProtection(ctx: Context): String {
        val pkg = ctx.packageName
        val myPid = Process.myPid()
        val results = StringBuilder()

        // Collect PIDs: this process + any child processes with the same uid
        // (WebView renderers run as isolated children with :sandboxed_process names)
        val pids = findAllRelatedPids(pkg, myPid)
        results.append("Found ").append(pids.size).append(" related PIDs\n")

        val commands = mutableListOf<String>()
        // 1. Pin oom_score_adj to -1000 for every PID (immune to LMK)
        for (pid in pids) {
            commands += "echo -1000 > /proc/$pid/oom_score_adj"
            commands += "renice -n -10 -p $pid 2>/dev/null || true"
        }
        // 2. Add to Doze whitelist — Android stops throttling background work
        commands += "dumpsys deviceidle whitelist +$pkg"
        // 3. Allow background start, foreground service, etc.
        commands += "cmd appops set $pkg RUN_IN_BACKGROUND allow"
        commands += "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
        // 4. Grant battery optimization exemption via settings
        commands += "settings put global app_standby_enabled 0 2>/dev/null || true"
        // 5. Try to raise the LMK minfree too (optional, device-specific)
        commands += "echo 0 > /sys/module/lowmemorykiller/parameters/enable 2>/dev/null || true"

        val ok = runAsRoot(commands)
        results.append(if (ok) "✓ Root commands succeeded" else "✗ Root command failed")
        results.append("\n")
        // Verify oom_score_adj for the main pid
        try {
            val adj = File("/proc/$myPid/oom_score_adj").readText().trim()
            results.append("main pid $myPid oom_score_adj = ").append(adj).append("\n")
        } catch (t: Throwable) {
            results.append("read oom_score_adj failed: ").append(t.message).append("\n")
        }
        Log.i(TAG, results.toString())
        return results.toString()
    }

    /** Find the main process PID and any child processes that belong to our package. */
    private fun findAllRelatedPids(pkg: String, mainPid: Int): List<Int> {
        val pids = mutableSetOf(mainPid)
        try {
            // Walk /proc/<pid>/cmdline looking for our package name
            val proc = File("/proc")
            val children = proc.listFiles() ?: return pids.toList()
            for (child in children) {
                val name = child.name
                if (!name.all { it.isDigit() }) continue
                val pid = name.toIntOrNull() ?: continue
                try {
                    val cmdline = File(child, "cmdline").readText().trim('\u0000').split('\u0000').first()
                    if (cmdline.startsWith(pkg)) pids.add(pid)
                } catch (_: Throwable) { /* access denied, skip */ }
            }
        } catch (_: Throwable) {}
        return pids.toList()
    }

    /** Pipe a batch of shell commands to `su` and wait. Returns true if exit 0. */
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
            Log.w(TAG, "su exec failed", t)
            false
        }
    }

    /**
     * Keep re-applying protection every N seconds since WebView renderers can
     * respawn (e.g. after a config change or if LMK did kill one before we pinned).
     */
    fun startPeriodicReapply(ctx: Context, intervalMs: Long = 60_000) {
        Thread {
            while (true) {
                try { applyProtection(ctx) } catch (_: Throwable) {}
                try { Thread.sleep(intervalMs) } catch (_: Throwable) { break }
            }
        }.apply { isDaemon = true; name = "SAM-RootProtection"; start() }
    }
}
