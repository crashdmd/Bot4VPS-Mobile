package com.bot4vps.mobile.ui.browser

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal data class BrowserTarget(val session: String, val origin: String, val entry: String, val endpoint: HttpUrl, val service: String = "")

/** Loopback HTTP origin belongs to the service. Panel credentials stay in native code. */
internal class ServiceBrowserProxy(val target: BrowserTarget, private val awaitGateway: () -> Unit = {}, private val logout: () -> Unit = {}, private val timedOut: () -> Unit = {}, private val connector: (CoroutineScope) -> Socket) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val executor = ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(24))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val cookie = "b4v_browser_$secret"
    val origin = "http://127.0.0.1:${server.localPort}"
    val startUrl = "$origin/_bot4vps_start/$secret"
    private val upstream = "http://127.0.0.1/".toHttpUrl()
    @Volatile private var closed = false
    private val factory = object : SocketFactory() {
        override fun createSocket(): Socket = connector(scope).also { sockets.add(it) }
        override fun createSocket(host: String?, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int) = createSocket(host, port)
        override fun createSocket(host: InetAddress?, port: Int) = createSocket(host?.hostAddress, port)
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int) = createSocket(host, port)
    }
    private val client = OkHttpClient.Builder().socketFactory(factory)
        .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }).proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES).connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(6, 60, TimeUnit.SECONDS)).build()

    init {
        scope.launch {
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.removeIf { it.isClosed }
                sockets.add(socket)
                try { executor.execute { serve(socket) } } catch (_: Exception) { socket.close(); sockets.remove(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 60000
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream().buffered()
                val request = readHead(input)
                val parts = request.first.split(' ')
                require(parts.size == 3 && parts[2].startsWith("HTTP/1."))
                val method = parts[0]; val path = parts[1]
                require(path.startsWith('/') && !path.startsWith("//") && '\\' !in path)
                require(request.second.firstOrNull { it.first.equals("host", true) }?.second == "127.0.0.1:${server.localPort}")
                if (path == "/_bot4vps_start/$secret" && method == "GET") {
                    output.write(("HTTP/1.1 302 Found\r\nLocation: ${target.entry}\r\nSet-Cookie: $cookie=$secret; Path=/; HttpOnly; SameSite=Strict\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    output.flush(); return
                }
                val cookies = request.second.filter { it.first.equals("cookie", true) }.joinToString("; ") { it.second }
                require(cookies.split(';').any { it.trim() == "$cookie=$secret" })
                val localOrigin = request.second.firstOrNull { it.first.equals("origin", true) }?.second
                require(localOrigin == null || localOrigin == origin)
                awaitGateway()
                val headers = request.second.filterNot { it.first.lowercase().startsWith("x-bot4vps-") || it.first.lowercase().startsWith("x-forwarded-") || it.first.lowercase() in setOf("host", "forwarded", "connection", "content-length", "transfer-encoding", "accept-encoding", "proxy-authorization", "proxy-connection", "expect") }
                    .mapNotNull { (name, value) ->
                        when (name.lowercase()) {
                            "cookie" -> value.split(';').filterNot { it.trim().startsWith("$cookie=") }.joinToString(";").takeIf { it.isNotBlank() }?.let { name to it }
                            "origin", "referer" -> name to value.replace(origin, target.origin)
                            else -> name to value
                        }
                    }
                if (request.second.any { it.first.equals("upgrade", true) && it.second.equals("websocket", true) }) {
                    websocket(method, path, headers, input, output, socket)
                    return
                }
                if (request.second.any { it.first.equals("expect", true) && it.second.equals("100-continue", true) }) { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
                val body = readBody(input, request.second)
                val url = upstream.newBuilder().encodedPath(path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").ifEmpty { null }).build()
                // OkHttp requests gzip and decodes it after the compressed bytes
                // cross the panel tunnel. Keep its upstream connection for assets.
                val builder = Request.Builder().url(url).header("Host", target.origin.toHttpUrl().let { if (it.port == (if (it.isHttps) 443 else 80)) if (':' in it.host) "[${it.host}]" else it.host else "${if (':' in it.host) "[${it.host}]" else it.host}:${it.port}" })
                headers.forEach { (name, value) -> builder.addHeader(name, value) }
                val payload = if (method in listOf("GET", "HEAD")) null else body.toRequestBody(null)
                client.newCall(builder.method(method, payload).build()).execute().use { response ->
                    writeResponse(output, response, method)
                    if (response.code in 200..399 && serviceLogout(target.service, path)) logout()
                }
            } catch (error: Exception) {
                if (error is SocketTimeoutException && error.message?.contains("60") == true) timedOut()
                runCatching {
                    val detail = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.firstOrNull { it.startsWith("Слишком много запросов к панели") || it.startsWith("Панель не подтвердила канал") || it.startsWith("Сессия сервиса завершена") }
                    val message = (detail ?: "Не удалось открыть сервис через панель. Проверьте связь с панелью и доступность сервиса.").toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${message.size}\r\nConnection: close\r\n\r\n").toByteArray() + message)
                }
            } finally { sockets.remove(socket) }
        }
    }

    private fun writeResponse(output: OutputStream, response: Response, method: String) {
        val headers = response.headers.toList().filterNot { it.first.lowercase() in setOf("connection", "transfer-encoding", "content-length") }
            .map { (name, value) -> name to when (name.lowercase()) {
                "location", "content-location" -> local(value)
                "content-security-policy" -> local(value).replace("upgrade-insecure-requests", "")
                // Chromium sends Origin: null for form navigations under
                // no-referrer. Keep navigation origin verifiable on loopback
                // and send referrers only within this isolated service origin.
                "referrer-policy" -> if (value.equals("no-referrer", true)) "same-origin" else value
                "set-cookie" -> value.split(';').filterNot { it.trim().startsWith("domain=", true) }.joinToString(";")
                else -> value
            } }
        val type = response.header("Content-Type").orEmpty()
        val rewrite = (type.startsWith("text/html") || type.startsWith("text/css") || type.contains("javascript")) && response.header("Content-Encoding").let { it == null || it == "identity" }
        val source = response.body?.byteStream()
        val bytes = if (rewrite && source != null) serviceContent(target.service, response.request.url.encodedPath, type,
            local(String(readLimited(source, 8 * 1024 * 1024), Charsets.UTF_8))).toByteArray() else null
        val noBody = method == "HEAD" || response.code in listOf(204, 304)
        output.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())
        headers.forEach { (name, value) -> output.write("$name: $value\r\n".toByteArray(Charsets.ISO_8859_1)) }
        output.write((if (noBody) "Content-Length: 0\r\n" else if (bytes != null) "Content-Length: ${bytes.size}\r\n" else "Transfer-Encoding: chunked\r\n").toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray()); output.flush()
        if (!noBody) {
            if (bytes != null) output.write(bytes)
            else if (source != null) {
                val buffer = ByteArray(32768)
                while (true) {
                    val count = source.read(buffer); if (count < 0) break
                    output.write("${count.toString(16)}\r\n".toByteArray()); output.write(buffer, 0, count); output.write("\r\n".toByteArray()); output.flush()
                }
                output.write("0\r\n\r\n".toByteArray())
            }
        }
        output.flush()
    }

    private fun websocket(method: String, path: String, headers: List<Pair<String, String>>, input: InputStream, output: OutputStream, localSocket: Socket) {
        val remote = factory.createSocket()
        remote.use {
            remote.connect(InetSocketAddress.createUnresolved(upstream.host, upstream.port), 25000)
            val upstreamOutput = remote.getOutputStream()
            upstreamOutput.write("$method $path HTTP/1.1\r\nHost: ${target.origin.substringAfter("://")}\r\nConnection: Upgrade\r\n".toByteArray())
            headers.forEach { (name, value) -> upstreamOutput.write("$name: $value\r\n".toByteArray(Charsets.ISO_8859_1)) }
            upstreamOutput.write("\r\n".toByteArray()); upstreamOutput.flush()
            remote.soTimeout = 60000
            val upstreamInput = remote.getInputStream().buffered()
            val response = readHead(upstreamInput)
            output.write("${response.first}\r\n".toByteArray())
            response.second.forEach { (name, value) -> output.write("$name: ${local(value)}\r\n".toByteArray(Charsets.ISO_8859_1)) }
            output.write("\r\n".toByteArray()); output.flush()
            if (!response.first.startsWith("HTTP/1.1 101")) return
            remote.soTimeout = 0
            localSocket.soTimeout = 0
            val incoming = scope.launch {
                try { val buffer = ByteArray(32768); while (true) { val count = upstreamInput.read(buffer); if (count < 0) break; output.write(buffer, 0, count); output.flush() } } finally { runCatching { localSocket.close() } }
            }
            try { input.copyTo(upstreamOutput); upstreamOutput.flush() }
            finally { remote.close(); incoming.cancel() }
        }
    }

    private fun local(value: String): String = value.replace(target.origin, origin)
        .replace(target.origin.replace("https://", "wss://").replace("http://", "ws://"), origin.replace("http://", "ws://"))

    override fun close() {
        closed = true
        server.close()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        executor.shutdownNow(); scope.cancel()
    }
}

internal fun serviceLogout(service: String, path: String): Boolean {
    val segments = runCatching { ("http://service.invalid" + path).toHttpUrl().pathSegments }.getOrNull() ?: return false
    return when (service) {
        "ssclash" -> segments.filter { it.isNotEmpty() } == listOf("logout")
        "adguardhome" -> segments.filter { it.isNotEmpty() } == listOf("control", "logout")
        "luci", "podkop" -> segments.take(2) == listOf("cgi-bin", "luci") && segments.takeLast(2) == listOf("admin", "logout")
        "3x-ui" -> segments.lastOrNull() == "logout"
        else -> false
    }
}

internal fun readHead(input: InputStream): Pair<String, List<Pair<String, String>>> {
    var total = 0
    fun line(): String {
        val result = ByteArrayOutputStream()
        while (true) {
            val next = input.read(); if (next < 0) throw EOFException()
            require(++total <= 65536)
            if (next == 10) return result.toString("ISO-8859-1").removeSuffix("\r")
            result.write(next)
        }
    }
    val first = line(); val headers = mutableListOf<Pair<String, String>>()
    while (true) {
        val row = line(); if (row.isEmpty()) return first to headers
        require(':' in row && !row.startsWith(' ') && !row.startsWith('\t'))
        headers.add(row.substringBefore(':') to row.substringAfter(':').trim())
    }
}

internal fun readBody(input: InputStream, headers: List<Pair<String, String>>): ByteArray {
    val sizes = headers.filter { it.first.equals("content-length", true) }.map { it.second.toLong() }.distinct()
    require(sizes.size <= 1)
    val transfer = headers.firstOrNull { it.first.equals("transfer-encoding", true) }?.second
    require(transfer == null || sizes.isEmpty())
    if (transfer != null) {
        require(transfer.equals("chunked", true))
        val data = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input)
            val count = line.substringBefore(';').toInt(16)
            require(count >= 0 && count <= 32 * 1024 * 1024 - data.size())
            if (count == 0) { while (readLine(input).isNotEmpty()) {}; return data.toByteArray() }
            data.write(readExact(input, count)); require(readLine(input).isEmpty())
        }
    }
    val length = sizes.firstOrNull() ?: 0
    require(length in 0..32 * 1024 * 1024)
    return readExact(input, length.toInt())
}
private fun readLine(input: InputStream): String {
    val data = ByteArrayOutputStream()
    while (data.size() < 8192) {
        val next = input.read(); if (next < 0) throw EOFException()
        if (next == 10) return data.toString("ISO-8859-1").removeSuffix("\r")
        data.write(next)
    }
    throw IOException("Заголовок слишком длинный")
}
private fun readExact(input: InputStream, length: Int): ByteArray = ByteArray(length).also { data ->
    var offset = 0
    while (offset < length) {
        val count = input.read(data, offset, length - offset); if (count < 0) throw EOFException()
        offset += count
    }
}
private fun readLimited(input: InputStream, max: Int): ByteArray {
    val data = ByteArrayOutputStream(); val buffer = ByteArray(32768)
    while (true) {
        val count = input.read(buffer); if (count < 0) return data.toByteArray()
        require(data.size() + count <= max); data.write(buffer, 0, count)
    }
}
