package com.bajar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Notificaciones de descarga (progreso y aviso final). */
object Notif {
    const val CH = "downloads"
    const val RUN = 1
    const val END = 2

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "ZIO", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun open(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun running(c: Context, text: String, pct: Int): Notification {
        channel(c)
        return Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ZIO")
            .setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(c))
            .build()
    }

    fun done(c: Context, text: String): Notification {
        channel(c)
        return Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ZIO")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(c))
            .build()
    }
}

/**
 * Servicio en primer plano: mientras hay una descarga, Android no pausa ni cierra la app
 * aunque cambies de aplicación o apagues la pantalla.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notif.RUN, Notif.running(this, "…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}
