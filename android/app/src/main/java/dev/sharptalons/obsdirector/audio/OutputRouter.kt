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
 * Where the mix plays. By default, whatever output the phone is using: Bluetooth, a wired headset,
 * a USB sound card, the headphone jack or the speaker. Android picks; this follows it. In
 * Settings the person can pin one output instead ([preferredKey]); while it's connected the mix
 * goes there, and when it isn't, back to automatic. When outputs
 * come or go it tells the audio engine to make a fresh track (a track made before a headset
 * connected can stay where it was, a low-latency one especially), and it reports the device
 * the sound is actually going to.
 *
 * One exception: a Bluetooth headset that only does calls (many single-ear ones) never gets
 * media from Android. When that's the headset connected, the mix goes over the call channel
 * (mono, lower quality, but heard).
 */
class OutputRouter(context: Context) {
    /** How the engine should make its track right now. */
    data class Route(
        /** Use the call channel (voice communication) instead of media. */
        val callChannel: Boolean,
        /** Ask for a low-latency track: only when no Bluetooth is connected. */
        val lowLatency: Boolean,
        /** The output chosen in Settings, or the call-only headset. Null: Android chooses. */
        val device: AudioDeviceInfo?,
    )

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    /** Set when outputs changed; the engine makes a fresh track and clears it. */
    @Volatile var dirty = false
    private val nameState = MutableStateFlow("Phone speaker")
    /** What the mix is playing through, for the Listen card and Settings. */
    val outputName: StateFlow<String> get() = nameState
    @Volatile private var callRouteOn = false

    /** One output the person can choose in Settings. [key] survives the device reconnecting. */
    data class Output(val key: String, val label: String)
    private val outputsState = MutableStateFlow<List<Output>>(emptyList())
    /** The outputs connected now, for the Play through choice. */
    val available: StateFlow<List<Output>> get() = outputsState

    /** "auto", or the [Output.key] of the output the mix should go to. */
    @Volatile var preferredKey: String = "auto"
        set(v) { if (field != v) { field = v; changed() } }

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
        outputsState.value = choosable().map { Output(key(it), describe(it)) }
        // Until a track reports where it went, name where it will most likely go.
        nameState.value = describe(preferred() ?: likelyDefault())
    }

    private fun outputs() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    /** A stable name for a device: its kind and product name (ids change when it reconnects). */
    fun key(d: AudioDeviceInfo) = "${d.type}:${d.productName}"

    /** Outputs that make sense for listening, one of each (some appear twice, e.g. USB). */
    private fun choosable(): List<AudioDeviceInfo> {
        val ok = mutableSetOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
        )
        if (Build.VERSION.SDK_INT >= 28) ok += AudioDeviceInfo.TYPE_HEARING_AID
        if (Build.VERSION.SDK_INT >= 31) { ok += AudioDeviceInfo.TYPE_BLE_HEADSET; ok += AudioDeviceInfo.TYPE_BLE_SPEAKER }
        return outputs().filter { it.isSink && it.type in ok }.distinctBy { key(it) }
    }

    /** The output chosen in Settings, if it's connected now. */
    fun preferred(): AudioDeviceInfo? =
        if (preferredKey == "auto") null else choosable().firstOrNull { key(it) == preferredKey }

    /** Chosen in Settings but not connected right now. */
    fun preferredMissing(): Boolean = preferredKey != "auto" && preferred() == null

    private fun isBtMedia(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    private fun isWiredOrUsb(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** Android's usual order for media: Bluetooth, then wired or USB, then the speaker. */
    private fun likelyDefault(): AudioDeviceInfo? {
        val outs = outputs()
        return outs.firstOrNull(::isBtMedia) ?: outs.firstOrNull(::isWiredOrUsb)
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /** A plain name for a device: its kind, plus its product name when it has one. */
    fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "Phone speaker"
        val kind = when {
            isBtMedia(d) -> "Bluetooth"
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headphones"
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            d.type == AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            d.type == AudioDeviceInfo.TYPE_LINE_ANALOG || d.type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                d.type == AudioDeviceInfo.TYPE_AUX_LINE -> "Line out"
            d.type == AudioDeviceInfo.TYPE_DOCK -> "Dock"
            Build.VERSION.SDK_INT >= 28 && d.type == AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            else -> "Phone speaker"
        }
        val n = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        return if (n != null && kind != "Phone speaker") "$kind · $n" else kind
    }

    /** The engine reports the device its track actually plays through. */
    fun reportRouted(d: AudioDeviceInfo?) { if (d != null) nameState.value = describe(d) }

    /** Work out how to make the track from what's chosen and what's connected. */
    fun current(): Route {
        preferred()?.let { p ->
            val call = p.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            val bt = call || isBtMedia(p)
            return Route(callChannel = call, lowLatency = !bt, device = p)
        }
        val outs = outputs()
        val anyBt = outs.any { isBtMedia(it) || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val callOnly = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        // A call-only headset is the one in use when nothing plays media better than it.
        val useCall = callOnly != null && outs.none { isBtMedia(it) || isWiredOrUsb(it) }
        return Route(callChannel = useCall, lowLatency = !anyBt, device = if (useCall) callOnly else null)
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
