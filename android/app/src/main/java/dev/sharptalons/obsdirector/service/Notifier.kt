package dev.sharptalons.obsdirector.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.sharptalons.obsdirector.MainActivity
import dev.sharptalons.obsdirector.R
import dev.sharptalons.obsdirector.core.Alert

object Notifier {
    const val CH_MONITOR = "monitor"
    const val CH_ALERTS = "alerts"
    const val CH_DESK = "desk"
    const val CH_UPDATES = "updates"
    const val ID_MONITOR = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the mix is playing, with clip controls"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Silence, clipping, recording stopped, disk space, dropped frames, connection lost"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DESK, "Desk messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Replies from the volunteers' dock in OBS"
        })
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A new version of OBS Director is ready"
        })
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(context: Context, a: Alert) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(a.id.hashCode(), n) }
    }

    fun cancelAlert(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    fun desk(context: Context, text: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CH_DESK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Desk")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(2, n) }
    }
}
