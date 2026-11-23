package com.patnaik.sam

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens the OEM-specific "autostart / background allow" screen so the user can whitelist the app.
 * Xiaomi, OnePlus, Oppo, Vivo, Realme, Huawei all have proprietary killers that ignore Android's
 * standard battery-optimization exemption. Based on open-source AutoStarter library mappings.
 */
object OemAutoStart {

    private data class Target(val pkg: String, val cls: String)

    private val routes: Map<String, List<Target>> = mapOf(
        "xiaomi"  to listOf(Target("com.miui.securitycenter",
                                   "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        "redmi"   to listOf(Target("com.miui.securitycenter",
                                   "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        "poco"    to listOf(Target("com.miui.securitycenter",
                                   "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        "oppo"    to listOf(
            Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Target("com.oppo.safe",          "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        ),
        "vivo"    to listOf(
            Target("com.iqoo.secure",             "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Target("com.vivo.permissionmanager",  "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Target("com.iqoo.secure",             "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        ),
        "realme"  to listOf(
            Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        ),
        "oneplus" to listOf(Target("com.oneplus.security",
                                   "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
        "huawei"  to listOf(
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        ),
        "honor"   to listOf(Target("com.huawei.systemmanager",
                                   "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        "samsung" to listOf(Target("com.samsung.android.lool",
                                   "com.samsung.android.sm.ui.battery.BatteryActivity")),
        "asus"    to listOf(
            Target("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
            Target("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
        )
    )

    fun needsPrompt(oem: String): Boolean {
        val k = oem.lowercase()
        return routes.keys.any { k.contains(it) }
    }

    fun messageFor(oem: String): String {
        val k = oem.lowercase()
        return when {
            k.contains("xiaomi") || k.contains("redmi") || k.contains("poco") ->
                "MIUI aggressively kills background apps. Open Security app → Permissions → Autostart → enable SAM Panel. Also: Settings → Apps → SAM Panel → Battery saver → No restrictions."
            k.contains("oneplus") ->
                "OxygenOS kills background apps. Open Settings → Battery → Battery optimization → All apps → SAM Panel → Don't optimize. Note: this may revert itself — re-check if the app stops working."
            k.contains("samsung") ->
                "Samsung puts unused apps to sleep. Open Settings → Battery → Background usage limits → Never sleeping apps → add SAM Panel."
            k.contains("oppo") ->
                "ColorOS kills background apps. Open Settings → Battery → App Battery Management → SAM Panel → Allow background activity."
            k.contains("vivo") ->
                "Funtouch OS kills background apps. Open Settings → Battery → Background power consumption → allow SAM Panel."
            k.contains("realme") ->
                "Realme UI kills background apps. Open Settings → Battery → App Battery Management → SAM Panel → Allow background."
            k.contains("huawei") || k.contains("honor") ->
                "EMUI kills background apps. Open Settings → Battery → App launch → SAM Panel → Manage manually → enable Auto-launch, Secondary launch, and Run in background."
            else ->
                "Your device may have additional battery-saving features that kill background apps. Please whitelist SAM Panel in your device's battery / autostart settings."
        }
    }

    fun open(ctx: Context): Boolean {
        val oem = (Build.MANUFACTURER ?: "").lowercase()
        val list = routes.entries.firstOrNull { oem.contains(it.key) }?.value
        if (list != null) {
            for (t in list) {
                try {
                    ctx.startActivity(
                        Intent().setComponent(ComponentName(t.pkg, t.cls))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return true
                } catch (_: ActivityNotFoundException) { /* try next fallback */ }
                  catch (_: SecurityException) { /* try next fallback */ }
                  catch (_: Throwable) { /* try next fallback */ }
            }
        }
        // OnePlus fallback
        if (oem.contains("oneplus")) {
            try {
                ctx.startActivity(
                    Intent("com.android.settings.action.BACKGROUND_OPTIMIZE")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Throwable) {}
        }
        // Last-resort: app details screen
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: Throwable) {
            return false
        }
    }
}
