package dev.sharptalons.obsdirector

import android.app.Application
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.service.Notifier

class DirectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ClipStore.init(this)
        Notifier.createChannels(this)
        Director.init(this)
    }
}
