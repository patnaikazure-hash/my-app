package com.patnaik.sam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the foreground poller on device boot and on app upgrade.
 * Blocked by most OEMs unless the user has enabled Autostart for this app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val svc = Intent(context, FirebasePollerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i("SAM-BOOT", "Poller restart requested after ${intent.action}")
        } catch (t: Throwable) {
            Log.w("SAM-BOOT", "startForegroundService failed: ${t.message}")
        }
    }
}
