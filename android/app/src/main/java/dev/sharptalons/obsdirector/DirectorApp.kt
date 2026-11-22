package dev.sharptalons.obsdirector

import android.app.Application
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.service.Notifier
import dev.sharptalons.obsdirector.update.UpdateController

class DirectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ClipStore.init(this)
        Notifier.createChannels(this)
        Director.init(this)
        UpdateController.init(this)
    }
}
