# Magisk install-time hook for SAM Panel Keep-Alive.
# Keep it simple: just mark the service script executable.

set_perm_recursive  "$MODPATH"  0  0  0755  0644
set_perm            "$MODPATH/service.sh"  0  0  0755
set_perm_recursive  "$MODPATH/system/etc/sysconfig"  0  0  0755  0644

ui_print "- SAM Panel Keep-Alive installed"
ui_print "- Reboot to activate"
ui_print "- Logs: /data/local/tmp/sam_keepalive.log"
