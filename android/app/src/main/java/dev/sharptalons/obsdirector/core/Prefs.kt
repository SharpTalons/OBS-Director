package dev.sharptalons.obsdirector.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings, saved on this device only. */
data class Settings(
    val deviceName: String = defaultDeviceName(),
    val lastIp: String = "",
    val lastPort: Int = 4466,
    /** A random ID for this phone, made once: OBS remembers it after someone presses Allow. */
    val deviceId: String = "",
    val lastPcName: String = "",
    // audio
    val bufferMs: Int = 0, // 0 = auto
    val autoListen: Boolean = true,
    /** The volume slider, 1–100% on the loudness scale ([dev.sharptalons.obsdirector.audio.Loudness]); 50 = as OBS sends it. */
    val volumePct: Int = dev.sharptalons.obsdirector.audio.Loudness.AS_SENT,
    val saveAudio: Boolean = true,
    val keepDays: Int = 30, // 0 = forever
    // clips
    val leadMinutes: Int = 1,
    val padSeconds: Int = 2,
    val askNote: Boolean = true,
    val chapters: Boolean = true,
    val sidecar: Boolean = true,
    val exportFormat: String = "Resolve EDL",
    // alerts
    val silenceSeconds: Int = 10, // 0 = off
    val alertClipping: Boolean = true,
    val alertRecStop: Boolean = true,
    val diskHours: Int = 2,
    val dropsPerMinute: Int = 100,
    // controls
    val holdMs: Int = 1000,
    val allowStreamStop: Boolean = false,
    // connection
    val autoReconnect: Boolean = true,
    val resumeListen: Boolean = true,
    // display
    val keepScreenOn: Boolean = true,
    /** Show a floating bubble with the time and a Clip button when the app is in the background. */
    val bubble: Boolean = true,
    /** Warn when the phone's battery drops below this percent; 0 = off. */
    val batteryWarn: Int = 15,
    /** Have the plugin save each recording's clip list (EDL and CSV) next to the recording. */
    val pcClipLists: Boolean = true,
    /** Where the mix plays: "auto" (whatever the phone uses) or one output's key. */
    val outputPref: String = "auto",
    /** How strongly the phone vibrates: 0 off, 1 light, 2 medium, 3 strong. */
    val haptics: Int = 2,
    /** Tap sound volume, percent; 0 = off. */
    val touchSound: Int = 50,
    val deskPresence: Boolean = true,
)

private fun defaultDeviceName(): String {
    val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    return model.take(40)
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val state = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> get() = state

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Settings()
        state.value = Settings(
            deviceName = sp.getString("deviceName", d.deviceName) ?: d.deviceName,
            lastIp = sp.getString("lastIp", "") ?: "",
            lastPort = sp.getInt("lastPort", d.lastPort),
            deviceId = sp.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).remove("lastPin").apply() },
            lastPcName = sp.getString("lastPcName", "") ?: "",
            bufferMs = sp.getInt("bufferMs", d.bufferMs),
            autoListen = sp.getBoolean("autoListen", d.autoListen),
            // before 0.8.0 the volume was a gain of 0–3; carry it over onto the loudness scale
            volumePct = if (sp.contains("volumePct")) sp.getInt("volumePct", d.volumePct)
                else if (sp.contains("volume")) dev.sharptalons.obsdirector.audio.Loudness.fromGain(sp.getFloat("volume", 1f)) else d.volumePct,
            saveAudio = sp.getBoolean("saveAudio", d.saveAudio),
            keepDays = sp.getInt("keepDays", d.keepDays),
            leadMinutes = sp.getInt("leadMinutes", d.leadMinutes),
            padSeconds = sp.getInt("padSeconds", d.padSeconds),
            askNote = sp.getBoolean("askNote", d.askNote),
            chapters = sp.getBoolean("chapters", d.chapters),
            sidecar = sp.getBoolean("sidecar", d.sidecar),
            exportFormat = sp.getString("exportFormat", d.exportFormat) ?: d.exportFormat,
            silenceSeconds = sp.getInt("silenceSeconds", d.silenceSeconds),
            alertClipping = sp.getBoolean("alertClipping", d.alertClipping),
            alertRecStop = sp.getBoolean("alertRecStop", d.alertRecStop),
            diskHours = sp.getInt("diskHours", d.diskHours),
            dropsPerMinute = sp.getInt("dropsPerMinute", d.dropsPerMinute),
            holdMs = sp.getInt("holdMs", d.holdMs),
            allowStreamStop = sp.getBoolean("allowStreamStop", d.allowStreamStop),
            autoReconnect = sp.getBoolean("autoReconnect", d.autoReconnect),
            resumeListen = sp.getBoolean("resumeListen", d.resumeListen),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            bubble = sp.getBoolean("bubble", d.bubble),
            batteryWarn = sp.getInt("batteryWarn", d.batteryWarn),
            pcClipLists = sp.getBoolean("pcClipLists", d.pcClipLists),
            outputPref = sp.getString("outputPref", d.outputPref) ?: d.outputPref,
            haptics = sp.getInt("haptics", d.haptics),
            touchSound = sp.getInt("touchSound", d.touchSound),
            deskPresence = sp.getBoolean("deskPresence", d.deskPresence),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(state.value)
        state.value = s
        sp.edit().apply {
            putString("deviceName", s.deviceName)
            putString("lastIp", s.lastIp)
            putInt("lastPort", s.lastPort)
            putString("deviceId", s.deviceId)
            putString("lastPcName", s.lastPcName)
            putInt("bufferMs", s.bufferMs)
            putBoolean("autoListen", s.autoListen)
            putInt("volumePct", s.volumePct)
            putBoolean("saveAudio", s.saveAudio)
            putInt("keepDays", s.keepDays)
            putInt("leadMinutes", s.leadMinutes)
            putInt("padSeconds", s.padSeconds)
            putBoolean("askNote", s.askNote)
            putBoolean("chapters", s.chapters)
            putBoolean("sidecar", s.sidecar)
            putString("exportFormat", s.exportFormat)
            putInt("silenceSeconds", s.silenceSeconds)
            putBoolean("alertClipping", s.alertClipping)
            putBoolean("alertRecStop", s.alertRecStop)
            putInt("diskHours", s.diskHours)
            putInt("dropsPerMinute", s.dropsPerMinute)
            putInt("holdMs", s.holdMs)
            putBoolean("allowStreamStop", s.allowStreamStop)
            putBoolean("autoReconnect", s.autoReconnect)
            putBoolean("resumeListen", s.resumeListen)
            putBoolean("keepScreenOn", s.keepScreenOn)
            putBoolean("bubble", s.bubble)
            putInt("batteryWarn", s.batteryWarn)
            putBoolean("pcClipLists", s.pcClipLists)
            putString("outputPref", s.outputPref)
            putInt("haptics", s.haptics)
            putInt("touchSound", s.touchSound)
            putBoolean("deskPresence", s.deskPresence)
        }.apply()
    }
}
