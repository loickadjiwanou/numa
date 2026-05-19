package com.numa

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class NumaService : Service() {

    companion object {
        const val CHANNEL_ID      = "numa_channel_v2"
        const val NOTIFICATION_ID = 1
        const val ACTION_RESTART  = "com.numa.RESTART_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, NumaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var volumeKeyReceiver: VolumeKeyReceiver

    override fun onCreate() {
        super.onCreate()
        volumeKeyReceiver = VolumeKeyReceiver(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildSilentNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        val restartIntent = Intent(ACTION_RESTART).apply { setPackage(packageName) }
        sendBroadcast(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification silencieuse ────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                " ",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description          = " "
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(" ")
            .setContentText(" ")
            .setSmallIcon(R.drawable.numa_transparent)
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    // ─── WakeLock — maintient le CPU actif même écran allumé verrouillé ──────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Numa::ServiceWakeLock"
        ).also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
