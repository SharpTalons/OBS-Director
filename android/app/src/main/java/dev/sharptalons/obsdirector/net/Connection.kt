package dev.sharptalons.obsdirector.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The control channel: newline-delimited JSON over TCP to the plugin.
 * Reconnects on its own (every 2 s) after a drop while [autoReconnect] is true.
 */
class Connection(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val device: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onWelcome(info: JsonObject)
        fun onMessage(msg: JsonObject)
        fun onLost(reason: String)
        fun onRejected(message: String)
        /** OBS is asking someone at the PC to press Allow for this phone. */
        fun onPending(message: String)
        fun onRetry(attempt: Int)
    }

    var autoReconnect = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var socket: Socket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    @Volatile private var closed = false
    val peerHost: String get() = host

    fun start() {
        job = scope.launch {
            var attempt = 0
            var everConnected = false
            while (isActive && !closed) {
                val result = runOnce()
                if (closed) break
                when (result) {
                    is Result.Rejected -> { listener.onRejected(result.message); break }
                    is Result.Lost -> {
                        if (result.wasConnected) { everConnected = true; listener.onLost(result.reason) }
                        if (!everConnected && attempt >= 2) { listener.onRejected(whyUnreachable(result.reason)); break }
                        if (!autoReconnect && everConnected) break
                    }
                }
                attempt++
                listener.onRetry(attempt)
                delay(2000)
            }
        }
    }

    /** What to tell the person when the PC can't be reached at all, by what went wrong. */
    private fun whyUnreachable(reason: String) = when (reason) {
        NO_ANSWER -> "The PC at $host didn't answer. Windows Firewall is most likely blocking OBS: on the PC, run the OBS Director installer again and keep \"Allow phones on this network through Windows Firewall\" ticked."
        REFUSED -> "The PC at $host is there, but OBS isn't accepting phones. Check OBS is open and has Tools, OBS Director in its menu (the plugin needs OBS 33 or later)."
        SILENT -> "The PC at $host took the connection but OBS didn't reply. Restart OBS and try again."
        NO_ROUTE -> "This phone can't reach $host. Check the phone is on the same Wi-Fi as the PC, not a guest or mobile network."
        else -> "Couldn't connect to the PC at $host ($reason). Check OBS is open with the plugin and the phone is on the same Wi-Fi."
    }

    private companion object {
        const val NO_ANSWER = "no-answer"
        const val REFUSED = "refused"
        const val NO_ROUTE = "no-route"
        const val SILENT = "silent"
    }

    private sealed class Result {
        data class Rejected(val message: String) : Result()
        data class Lost(val reason: String, val wasConnected: Boolean) : Result()
    }

    private suspend fun runOnce(): Result = withContext(Dispatchers.IO) {
        var connected = false
        var tcpUp = false
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 4000)
            tcpUp = true
            // Long enough to wait for someone to press Allow in OBS (the plugin gives up at 2 minutes).
            s.soTimeout = 130_000
            socket = s
            out = s.getOutputStream()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            write(buildJsonObject {
                put("type", "hello"); put("deviceId", deviceId); put("device", device); put("proto", Protocol.VERSION)
            })
            while (isActive && !closed) {
                val line = reader.readLine() ?: return@withContext Result.Lost("closed", connected)
                if (line.isBlank()) continue
                val msg = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                when (msg["type"]?.jsonPrimitive?.content) {
                    "welcome" -> { connected = true; s.soTimeout = 6000; listener.onWelcome(msg) }
                    "pending" -> listener.onPending(msg["message"]?.jsonPrimitive?.content ?: "Waiting for someone to press Allow in OBS.")
                    "error" -> return@withContext Result.Rejected(msg["message"]?.jsonPrimitive?.content ?: "Rejected by the PC")
                    "result" -> {
                        val id = msg["id"]?.jsonPrimitive?.int ?: -1
                        pending.remove(id)?.complete(msg)
                    }
                    else -> listener.onMessage(msg)
                }
            }
            Result.Lost("stopped", connected)
        } catch (e: SocketTimeoutException) {
            Result.Lost(if (connected) "timeout" else if (tcpUp) SILENT else NO_ANSWER, connected)
        } catch (e: ConnectException) {
            // "Connection refused": the PC answered, but nothing is listening on the port.
            Result.Lost(if (e.message?.contains("refused", ignoreCase = true) == true) REFUSED else (e.message ?: "network error"), connected)
        } catch (e: NoRouteToHostException) {
            Result.Lost(NO_ROUTE, connected)
        } catch (e: Exception) {
            Result.Lost(e.message ?: "network error", connected)
        } finally {
            runCatching { socket?.close() }
            socket = null
            out = null
            pending.values.forEach { it.complete(buildJsonObject { put("ok", false); put("error", "disconnected") }) }
            pending.clear()
        }
    }

    @Synchronized
    private fun write(obj: JsonObject): Boolean {
        val o = out ?: return false
        return runCatching {
            o.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
            o.flush()
        }.isSuccess
    }

    /** Sends a command and waits up to 5 s for its result. */
    suspend fun command(cmd: String, args: Map<String, JsonElement> = emptyMap()): JsonObject = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        val sent = write(buildJsonObject {
            put("type", "cmd"); put("id", id); put("cmd", cmd); put("args", JsonObject(args))
        })
        if (!sent) {
            pending.remove(id)
            return@withContext buildJsonObject { put("ok", false); put("error", "not_connected") }
        }
        withTimeoutOrNull(5000) { waiter.await() } ?: run {
            pending.remove(id)
            buildJsonObject { put("ok", false); put("error", "timeout") }
        }
    }

    fun ping() {
        scope.launch { write(buildJsonObject { put("type", "ping"); put("t", JsonPrimitive(System.currentTimeMillis())) }) }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        job?.cancel()
        scope.cancel()
    }
}

fun JsonObject.ok(): Boolean = this["ok"]?.jsonPrimitive?.content == "true"
fun JsonObject.data(): JsonObject? = runCatching { this["data"]?.jsonObject }.getOrNull()
