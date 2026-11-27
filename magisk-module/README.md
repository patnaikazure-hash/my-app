# SAM Panel Keep-Alive — Magisk Module

Flash once, reboot, forget. Keeps the SAM Panel app and every WebView renderer
sandbox child immune to Android's full kill stack.

## What it does

1. **sysconfig XML allowlist** — overlays `/system/etc/sysconfig/zz-sampanel.xml`
   so AMS sees SAM Panel as exempt from Doze, data saver and location throttling
   at the FRAMEWORK level. Persists across reboots without needing a shell command.
2. **Disables Android 14+ cached-apps freezer system-wide** via
   `device_config put activity_manager use_freezer false`. This is the biggest win —
   a frozen process dies on the next sync binder call, and `oom_score_adj=-1000`
   does nothing to stop it. Turn it off.
3. **Per-PID keep-alive loop (every 15s)** — writes `oom_score_adj=-1000` to the
   app PID AND every `:sandboxed_process` renderer PID, pins them to the top-app
   cpuset, and unfreezes the UID cgroup if the system froze it.

## Install

Open Magisk Manager → **Modules** → **Install from storage** → pick the ZIP.
Reboot.

## Verify

```
adb shell cat /data/local/tmp/sam_keepalive.log
adb shell 'for p in $(pgrep -f sandboxed_process); do cat /proc/$p/oom_score_adj; done'
```
Should print `-1000` for every renderer PID.

## Uninstall

Magisk Manager → **Modules** → toggle off `SAM Panel Keep-Alive` → reboot.
