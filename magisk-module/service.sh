#!/system/bin/sh
# SAM Panel Keep-Alive — late-start service.
# Runs after late_start by Magisk, well after boot_completed.
# Keeps the SAM Panel app + all its WebView renderer sandbox children immune
# to the full Android kill stack: LMKD, cached-apps freezer, per-app memory
# limiter, OEM daemons.

PKG=com.patnaik.sam
LOG=/data/local/tmp/sam_keepalive.log

log() { echo "[`date +%H:%M:%S`] $*" >> "$LOG"; }

# Wait for a fully booted system (keyguard ready = AMS has started).
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done
sleep 10

log "service.sh starting for $PKG"

# ─── 1. Framework-level allowlists (persistent per boot) ─────────────────
cmd deviceidle whitelist +"$PKG" >>"$LOG" 2>&1
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >>"$LOG" 2>&1
cmd appops set "$PKG" RUN_IN_BACKGROUND allow >>"$LOG" 2>&1
settings put global app_standby_enabled 0 2>/dev/null

# ─── 2. Disable Android 14+ cached-apps freezer system-wide ──────────────
# This is the single biggest crash vector for bound background apps.
# A frozen process dies on next synchronous binder call — no amount of
# oom_score_adj prevents it.  Turn it off.
device_config put activity_manager use_freezer false >>"$LOG" 2>&1
device_config put activity_manager use_compaction false >>"$LOG" 2>&1

# ─── 3. Per-PID keep-alive loop ─────────────────────────────────────────
# AMS re-overwrites oom_score_adj on every lifecycle change (seconds) for the
# app PID, but writes to renderer sandbox PIDs tend to hold much longer
# because AMS re-evaluates them less often.  Re-apply every 15s.
(
  while true; do
    # Parent app PID
    APP_PID="$(pidof "$PKG" 2>/dev/null)"
    if [ -n "$APP_PID" ]; then
      echo -1000 > "/proc/$APP_PID/oom_score_adj" 2>/dev/null
      # Pin to top-app cpuset so scheduler doesn't throttle
      /system/bin/task_profiles MaxPerformance "$APP_PID" 2>/dev/null
      echo "$APP_PID" > /dev/cpuset/top-app/tasks 2>/dev/null
      # Unfreeze our UID cgroup in case the system froze it
      UID_DIR="/sys/fs/cgroup/uid_$(stat -c '%u' /data/data/$PKG 2>/dev/null)"
      [ -d "$UID_DIR" ] && echo 0 > "$UID_DIR/cgroup.freeze" 2>/dev/null
    fi
    # WebView renderer children — enumerated by name because PPID is
    # webview_zygote, not the app.
    for pid in $(pgrep -f 'sandboxed_process' 2>/dev/null); do
      echo -1000 > "/proc/$pid/oom_score_adj" 2>/dev/null
      /system/bin/task_profiles MaxPerformance "$pid" 2>/dev/null
      /system/bin/task_profiles ProcessCapacityHigh "$pid" 2>/dev/null
      echo "$pid" > /dev/cpuset/top-app/tasks 2>/dev/null
      echo "$pid" > /dev/stune/top-app/tasks 2>/dev/null
    done
    sleep 15
  done
) &

log "service.sh started keep-alive loop pid=$!"
