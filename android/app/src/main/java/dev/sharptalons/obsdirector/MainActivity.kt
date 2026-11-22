package dev.sharptalons.obsdirector

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.ui.AppShell
import dev.sharptalons.obsdirector.ui.DirectorTheme
import dev.sharptalons.obsdirector.update.Updater
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var videoWasOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { DirectorTheme { AppShell() } }

        // keep the screen on while connected, if the setting says so
        lifecycleScope.launch {
            combine(Director.link, Prefs.settings) { link, s -> link != LinkState.DISCONNECTED && s.keepScreenOn }
                .collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }

        // a quiet update check, at most once a day
        val s = Prefs.settings.value
        if (s.autoCheckUpdates && System.currentTimeMillis() - s.lastUpdateCheck > 86_400_000L) {
            lifecycleScope.launch { Updater.check(this@MainActivity, quiet = true) }
        }
    }

    /** Screen off or another app in front: the mix carries on in the service, video always stops. */
    override fun onStop() {
        videoWasOn = Director.video.value
        if (videoWasOn) Director.setVideo(false)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (videoWasOn) {
            videoWasOn = false
            Director.toast("Video stopped with the screen. Turn it on again if you need it.")
        }
    }
}
