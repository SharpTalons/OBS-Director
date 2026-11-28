package dev.sharptalons.obsdirector.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.sharptalons.obsdirector.R
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the mix playing and clips working with the screen off. Its notification has Start clip /
 * End clip and Stop listening. Video never runs in the background; the activity turns it off.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_CLIP = "dev.sharptalons.obsdirector.CLIP"
        const val ACTION_LISTEN = "dev.sharptalons.obsdirector.LISTEN"
        const val ACTION_DISCONNECT = "dev.sharptalons.obsdirector.DISCONNECT"

        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ServiceCompat.startForeground(this, Notifier.ID_MONITOR, build("Connecting…", false, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "obs-director").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obs-director:monitor").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }

        scope.launch {
            combine(Director.link, Director.clipStart, Director.listening, Director.pc) { link, clip, listen, pc ->
                val text = when (link) {
                    LinkState.CONNECTED -> (if (clip != null) "Clip running from ${Director.tc(clip)}" else if (listen) "Listening to the mix" else "Connected, not listening") +
                        " · ${pc?.name ?: ""}"
                    LinkState.LOST -> "Connection lost, reconnecting…"
                    LinkState.CONNECTING -> "Connecting…"
                    LinkState.DISCONNECTED -> "Disconnected"
                }
                Triple(text, clip != null, listen)
            }.collect { (text, clipRunning, listen) ->
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this@MonitorService)
                        .notify(Notifier.ID_MONITOR, build(text, clipRunning, listen))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLIP -> if (Director.clipStart.value == null) Director.startClip() else Director.endClip()
            ACTION_LISTEN -> Director.setListening(!Director.listening.value)
            ACTION_DISCONNECT -> { Director.disconnect(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun action(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, MonitorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(text: String, clipRunning: Boolean, listen: Boolean): Notification =
        NotificationCompat.Builder(this, Notifier.CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("OBS Director")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openApp(this))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (clipRunning) "End clip" else "Start clip", action(ACTION_CLIP, 1))
            .addAction(0, if (listen) "Stop listening" else "Listen", action(ACTION_LISTEN, 2))
            .addAction(0, "Disconnect", action(ACTION_DISCONNECT, 3))
            .build()

    /** Swiped away from recent apps: save a running clip before Android may stop the app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Director.onAppClosing()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Director.onAppClosing()
        running = false
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}
