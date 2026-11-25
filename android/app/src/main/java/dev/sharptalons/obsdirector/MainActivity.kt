package dev.sharptalons.obsdirector

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.ui.AppShell
import dev.sharptalons.obsdirector.ui.DirectorTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What a launcher shortcut asked for, read once by the app's screens. */
object MainIntents {
    const val ACTION_CLIP = "dev.sharptalons.obsdirector.shortcut.CLIP"
    const val ACTION_STAGE = "dev.sharptalons.obsdirector.shortcut.STAGE"
    const val ACTION_EXIT = "dev.sharptalons.obsdirector.shortcut.EXIT"
    const val ACTION_DESK = "dev.sharptalons.obsdirector.open.DESK"
    var startScreen by androidx.compose.runtime.mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    private var closing = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var videoWasOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (handle(intent)) return
        setContent { DirectorTheme { AppShell() } }

        // keep the screen on while connected, if the setting says so
        lifecycleScope.launch {
            combine(Director.link, Prefs.settings) { link, s -> link != LinkState.DISCONNECTED && s.keepScreenOn }
                .collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Long-press shortcuts on the app icon. Returns true when the app is closing. */
    private fun handle(intent: android.content.Intent?): Boolean {
        when (intent?.action) {
            MainIntents.ACTION_EXIT -> {
                closing = true
                Director.disconnect()
                dev.sharptalons.obsdirector.service.BubbleService.hide(this)
                dev.sharptalons.obsdirector.service.MonitorService.stop(this)
                finishAndRemoveTask()
                return true
            }
            MainIntents.ACTION_STAGE -> MainIntents.startScreen = "stage"
            MainIntents.ACTION_DESK -> MainIntents.startScreen = "desk"
            MainIntents.ACTION_CLIP -> {
                MainIntents.startScreen = "monitor"
                if (Director.clipStart.value == null) {
                    if (Director.status.value.recActive && Director.link.value == LinkState.CONNECTED) Director.startClip()
                    else Director.toast("Clips start while OBS is recording and this phone is connected")
                }
            }
        }
        return false
    }

    /** Screen off or another app in front: the mix carries on in the service, video always stops. */
    override fun onStop() {
        videoWasOn = Director.video.value
        if (videoWasOn) Director.setVideo(false)
        if (!closing && !isChangingConfigurations && !isFinishing) dev.sharptalons.obsdirector.service.BubbleService.showIfWanted(this)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        dev.sharptalons.obsdirector.service.BubbleService.hide(this)
        if (videoWasOn) {
            videoWasOn = false
            Director.toast("Video stopped with the screen. Turn it on again if you need it.")
        }
    }
}
