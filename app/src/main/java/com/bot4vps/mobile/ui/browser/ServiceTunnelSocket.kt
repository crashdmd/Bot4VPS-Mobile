package com.bot4vps.mobile.ui.browser

import com.bot4vps.mobile.data.api.MobileWebSocketSession
import com.bot4vps.mobile.data.api.ReuseMobileSocketSession
import com.bot4vps.mobile.data.api.ApiError
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** A byte stream carried only over the paired panel's authenticated TLS socket. */
internal class ServiceTunnelSocket(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val scope: CoroutineScope,
    private val token: suspend () -> String,
) : Socket() {
    private val packets = ArrayBlockingQueue<ByteArray>(16)
    @Volatile private var readTimeout = 0
    private val input = object : InputStream() {
        private var current = ByteArray(0)
        private var position = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            val started = System.nanoTime()
            while (position == current.size) {
                if (ended && packets.isEmpty()) return -1
                val packet = packets.poll(100, TimeUnit.MILLISECONDS)
                if (packet != null && packet.isNotEmpty()) { current = packet; position = 0; break }
                if (readTimeout > 0 && (System.nanoTime() - started) / 1_000_000 >= readTimeout)
                    throw SocketTimeoutException("Шлюз не ответил в течение 60 секунд")
            }
            val count = minOf(length, current.size - position)
            current.copyInto(bytes, offset, position, position + count); position += count
            return count
        }
    }
    private val ready = CountDownLatch(1)
    private var failure: IOException? = null
    private var websocket: WebSocket? = null
    private var renewal: MobileWebSocketSession? = null
    @Volatile private var ended = false
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (ended) throw IOException("Соединение с сервисом закрыто")
            var at = offset
            while (at < offset + length) {
                val count = minOf(32768, offset + length - at)
                val ws = websocket ?: throw IOException("Сервис ещё не подключён")
                while (ws.queueSize() > 512 * 1024) {
                    if (ended) throw IOException("Соединение прервано")
                    Thread.sleep(5)
                }
                if (!ws.send(bytes.toByteString(at, count))) throw IOException("Соединение с панелью прервано")
                at += count
            }
        }
        override fun close() = this@ServiceTunnelSocket.close()
    }

    override fun connect(address: SocketAddress?, timeout: Int) {
        check(websocket == null)
        websocket = client.newWebSocket(Request.Builder().url(endpoint).tag(ReuseMobileSocketSession::class.java, ReuseMobileSocketSession).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                renewal = MobileWebSocketSession(scope,
                    token = token, send = webSocket::send,
                    expired = { close() }).also {
                    response.header("X-Bot4VPS-Mobile-Session-Ttl")?.toLongOrNull()?.let(it::start)
                }
                ready.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 65536) { close(); return }
                val packet = bytes.toByteArray()
                while (!ended && !packets.offer(packet, 100, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { renewal?.receive(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = IOException(when (response?.code ?: (t as? ApiError)?.status) {
                    429 -> "Слишком много запросов к панели. Подождите минуту и откройте сервис заново."
                    401, 403 -> "Панель не подтвердила канал сервиса. Закройте окно и откройте сервис заново."
                    404, 410 -> "Сессия сервиса завершена. Откройте сервис заново."
                    else -> "Не удалось подключить сервис через панель"
                }, t)
                ready.countDown(); close()
            }
        })
        if (!ready.await((if (timeout > 0) timeout else 20000).toLong(), TimeUnit.MILLISECONDS)) {
            close(); throw SocketTimeoutException("Сервис не ответил через панель")
        }
        failure?.let { throw it }
        if (ended) throw IOException("Сервис закрыл подключение")
    }
    override fun connect(address: SocketAddress?) = connect(address, 20000)
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected() = websocket != null && !ended
    override fun isClosed() = ended
    override fun isInputShutdown() = ended
    override fun isOutputShutdown() = ended
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort() = 80
    override fun getLocalPort() = 0
    override fun setSoTimeout(timeout: Int) { require(timeout >= 0); readTimeout = timeout }
    override fun getSoTimeout() = readTimeout
    override fun setTcpNoDelay(on: Boolean) {}
    override fun close() {
        if (ended) return
        ended = true
        renewal?.stop()
        websocket?.cancel()
        ready.countDown()
        // Readers drain already received bytes, then observe EOF.
        packets.offer(ByteArray(0))
    }
}
