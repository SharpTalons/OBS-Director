package dev.sharptalons.obsdirector.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the mix plays: follows Bluetooth headsets, wired headphones and the speaker as they
 * come and go, and tells the audio engine to rebuild its track when that changes (a track
 * made before a headset connected can stay on the speaker, especially a low-latency one).
 *
 * Bluetooth headsets that play media (A2DP, LE Audio) take the normal media route. A headset
 * that only does calls (many single-ear ones) never gets media from Android; for those the mix
 * goes over the call channel (mono, lower quality, but heard) when there's nothing better.
 */
class OutputRouter(context: Context) {
    enum class Choice { AUTO, PHONE, BLUETOOTH }

    /** How the engine should make its track right now. */
    data class Route(
        val name: String,
        /** Use the call channel (voice communication) instead of media. */
        val callChannel: Boolean,
        /** Ask for a low-latency track: only when nothing Bluetooth is in the way. */
        val lowLatency: Boolean,
        /** Send the track to this device, or null for Android's choice. */
        val device: AudioDeviceInfo?,
    )

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile var choice = Choice.AUTO
        set(value) { field = value; changed() }
    /** Set when the route changed; the engine rebuilds its track and clears it. */
    @Volatile var dirty = false
    private val nameState = MutableStateFlow("Phone speaker")
    /** What the mix is playing through, for the Listen card. */
    val outputName: StateFlow<String> get() = nameState
    @Volatile private var callRouteOn = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = changed()
    }

    fun start() { am.registerAudioDeviceCallback(callback, main); changed() }

    fun stop() {
        runCatching { am.unregisterAudioDeviceCallback(callback) }
        releaseCallRoute()
    }

    private fun changed() {
        dirty = true
        nameState.value = current().name
    }

    private fun outputs() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private fun label(d: AudioDeviceInfo, kind: String): String {
        val n = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        return if (n != null) "$kind · $n" else kind
    }

    /** Work out the route from what's connected and what the person chose. */
    fun current(): Route {
        val outs = outputs()
        val media = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        val callOnly = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wired = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val speaker = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return when {
            choice == Choice.PHONE -> wired?.let { Route(label(it, "Headphones"), false, true, it) }
                ?: Route("Phone speaker", false, true, speaker)
            media != null && choice != Choice.PHONE -> Route(label(media, "Bluetooth"), false, false, media)
            callOnly != null && (choice == Choice.BLUETOOTH || wired == null) ->
                Route(label(callOnly, "Bluetooth (call audio)"), true, false, callOnly)
            wired != null -> Route(label(wired, "Headphones"), false, true, wired)
            else -> Route("Phone speaker", false, true, null)
        }
    }

    /** Call-only headsets need the phone's call route switched to them while we play. */
    fun applyCallRoute(route: Route) {
        if (!route.callChannel) { releaseCallRoute(); return }
        val d = route.device ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.setCommunicationDevice(d)
            else {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
            }
            callRouteOn = true
        }
    }

    fun releaseCallRoute() {
        if (!callRouteOn) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION") am.isBluetoothScoOn = false
                @Suppress("DEPRECATION") am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
            }
        }
        callRouteOn = false
    }
}
