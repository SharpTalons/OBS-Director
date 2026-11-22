package dev.sharptalons.obsdirector.net

import android.content.Context
import android.net.wifi.WifiManager
import dev.sharptalons.obsdirector.core.FoundPc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Listens for the plugin's UDP beacon (port 4467) and keeps a list of PCs seen in the last 10 s. */
class Discovery(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val found = MutableStateFlow<List<FoundPc>>(emptyList())
    val pcs: StateFlow<List<FoundPc>> get() = found

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("obs-director-discovery").apply { setReferenceCounted(false) }
            lock.acquire()
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(Protocol.BEACON_PORT))
                }
            }.getOrNull()
            try {
                val buf = ByteArray(2048)
                while (isActive && socket != null) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                        if (obj["obsdirector"]?.jsonPrimitive?.intOrNull == null) continue
                        val pc = FoundPc(
                            name = obj["name"]?.jsonPrimitive?.content ?: "OBS PC",
                            ip = pkt.address.hostAddress ?: continue,
                            port = obj["port"]?.jsonPrimitive?.intOrNull ?: Protocol.CONTROL_PORT,
                            obs = obj["obs"]?.jsonPrimitive?.content ?: "",
                            plugin = obj["plugin"]?.jsonPrimitive?.content ?: "",
                        )
                        found.value = (found.value.filterNot { it.ip == pc.ip } + pc).sortedBy { it.name }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                    val cutoff = System.currentTimeMillis() - 10_000
                    if (found.value.any { it.lastSeen < cutoff }) found.value = found.value.filter { it.lastSeen >= cutoff }
                }
            } finally {
                socket?.close()
                lock.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
