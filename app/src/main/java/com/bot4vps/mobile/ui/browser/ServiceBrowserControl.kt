package com.bot4vps.mobile.ui.browser

import com.bot4vps.mobile.data.api.MobileWebSocketSession
import com.bot4vps.mobile.data.api.ReuseMobileSocketSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BrowserRecovery(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, val timeoutMillis: Long = 60_000) {
    private var lastReply = now()
    @Synchronized fun reply() { lastReply = now() }
    @Synchronized fun serverRemaining(millis: Long) { lastReply = now() - (timeoutMillis - millis.coerceIn(0, timeoutMillis)) }
    @Synchronized fun remaining(): Long = (timeoutMillis - (now() - lastReply)).coerceAtLeast(0)
}

internal enum class BrowserConnection { PANEL, GATEWAY, READY }

/** A window owns one control socket; service pages never see its identity. */
internal class ServiceBrowserControl(
    client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val token: suspend () -> String,
    private val changed: (BrowserConnection, Int) -> Unit,
    private val ended: (String) -> Unit,
    private val recovery: BrowserRecovery = BrowserRecovery(),
) : Closeable {
    private val client = client.newBuilder().pingInterval(10, TimeUnit.SECONDS).connectTimeout(12, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val monitor = Object()
    private var socket: WebSocket? = null
    private var renewal: MobileWebSocketSession? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    @Volatile private var gateway = false
    @Volatile private var failure = "Шлюз не ответил в течение 60 секунд. Туннель завершён."
    @Volatile private var generation = 0
    @Volatile private var nextAttempt = 0L
    @Volatile private var lastStatus = 0L
    private fun now() = System.nanoTime() / 1_000_000

    init {
        scope.launch {
            var lastPing = 0L
            while (!stopped.get()) {
                if (recovery.remaining() == 0L) { finish(failure); break }
                if (!connected && !connecting && now() >= nextAttempt) connect()
                if (connected && now() - lastStatus > 15_000) gateway = false
                if (connected && now() - lastPing >= 5_000) {
                    socket?.send("{\"type\":\"browser_ping\"}"); lastPing = now()
                }
                val connection = when {
                    !connected -> BrowserConnection.PANEL
                    !gateway -> BrowserConnection.GATEWAY
                    else -> BrowserConnection.READY
                }
                changed(connection, ((recovery.remaining() + 999) / 1000).toInt())
                delay(250)
            }
        }
    }

    @Synchronized private fun connect() {
        if (stopped.get() || connecting) return
        connecting = true
        val attempt = ++generation
        socket = client.newWebSocket(Request.Builder().url(endpoint)
            .tag(ReuseMobileSocketSession::class.java, ReuseMobileSocketSession).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped.get() || attempt != generation) { webSocket.cancel(); return }
                connected = true; connecting = false; lastStatus = now()
                renewal = MobileWebSocketSession(scope, token, webSocket::send) {
                    finish("Сессия панели завершена. Откройте сервис заново.")
                }.also { response.header("X-Bot4VPS-Mobile-Session-Ttl")?.toLongOrNull()?.let(it::start) }
                webSocket.send("{\"type\":\"browser_ping\"}")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get() || attempt != generation || renewal?.receive(text) == true) return
                val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                if (value["type"]?.jsonPrimitive?.contentOrNull != "browser_status") return
                lastStatus = now()
                gateway = value["gateway_available"]?.jsonPrimitive?.booleanOrNull == true
                value["retry_millis"]?.jsonPrimitive?.longOrNull?.let(recovery::serverRemaining)
                synchronized(monitor) { monitor.notifyAll() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (attempt != generation || stopped.get()) return
                if (code == 4401) finish("Доступ телефона к панели завершён. Туннель закрыт.")
                else if (code == 4410) finish(if (reason == "gateway_unavailable_60") failure else "Сессия сервиса завершена. Откройте сервис заново.")
                else disconnected()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (attempt == generation && !stopped.get()) disconnected()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (attempt != generation || stopped.get()) return
                if (response?.code in listOf(401, 403)) finish("Панель не подтвердила доступ телефона. Туннель закрыт.")
                else if (response?.code in listOf(404, 410)) finish("Сессия сервиса завершена. Откройте сервис заново.")
                else disconnected()
            }
        })
    }

    @Synchronized private fun disconnected() {
        connected = false; connecting = false; gateway = false; nextAttempt = now() + 2000
        renewal?.stop(); renewal = null
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun awaitGateway() {
        synchronized(monitor) {
            while (!stopped.get() && !(connected && gateway)) {
                if (recovery.remaining() == 0L) throw IOException(failure)
                monitor.wait(minOf(250L, recovery.remaining()).coerceAtLeast(1))
            }
            if (stopped.get()) throw IOException(failure)
        }
    }

    fun finish(message: String) {
        failure = message
        if (stopped.compareAndSet(false, true)) {
            shutdown()
            ended(message)
        }
    }

    private fun shutdown() {
        synchronized(monitor) { monitor.notifyAll() }
        renewal?.stop()
        val previous = socket
        previous?.send("{\"type\":\"browser_close\"}")
        previous?.close(1000, "window_closed")
        scope.launch(NonCancellable) { delay(1000); previous?.cancel() }
        scope.cancel()
    }
    override fun close() { if (stopped.compareAndSet(false, true)) shutdown() }
}
