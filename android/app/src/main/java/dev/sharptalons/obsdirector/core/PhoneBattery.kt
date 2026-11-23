package dev.sharptalons.obsdirector.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The phone's own battery, for the "plug it in" warning: running flat mid-service loses clips. */
object PhoneBattery {
    data class State(val percent: Int = 100, val charging: Boolean = true)

    private val state = MutableStateFlow(State())
    val battery: StateFlow<State> get() = state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read(intent)
    }

    fun init(context: Context) {
        context.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
    }

    private fun read(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level >= 0) state.value = State(level * 100 / scale, plugged)
    }
}
