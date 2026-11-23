package com.patnaik.sam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * dataSync foreground service that keeps the app process alive while the user has SAM Panel open.
 * The actual Firebase polling happens inside the WebView's JavaScript — this service just holds
 * a wake lock and a persistent notification so Android doesn't kill the process under memory pressure.
 */
class FirebasePollerService : Service() {

    companion object {
        const val NOTIF_ID = 0x5A41  // "SA"
        const val CHANNEL_ID = "sam_poller"
        // Hard-coded tag with no PII, no Proguard-renameable identifiers, no counters.
        const val WAKE_TAG = "com.patnaik.sam:FirebasePoller.tick"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SAM Panel is running")
            .setContentText("Watching Firebase projects. Tap to open.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .build()

        // On Android 14+ the FGS type bitmask MUST be passed here AND declared in the manifest
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (t: Throwable) {
            Log.e("SAM-FGS", "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLockSafely()
        return START_STICKY
    }

    private fun acquireWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            wl.setReferenceCounted(false)
            // Safety net: 60-min acquire timeout so a leaked lock caps on its own
            wl.acquire(60L * 60L * 1000L)
            wakeLock = wl
        } catch (t: Throwable) {
            Log.e("SAM-FGS", "wakelock acquire failed", t)
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    /**
     * Android 15 (API 35+) calls this when dataSync hits the 6-hour/24-hour cap.
     * We target 34 so this currently doesn't fire, but having it stops a future upgrade from
     * throwing RemoteServiceException. Must stop within "a few seconds".
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("SAM-FGS", "dataSync timeout reached — releasing and stopping")
        releaseWakeLockSafely()
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLockSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "SAM Panel Poller",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps SAM Panel polling while the screen is off"
                    lightColor = Color.GREEN
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }
}
