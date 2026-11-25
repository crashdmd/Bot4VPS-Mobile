package com.bot4vps.mobile.ui.browser

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class ServiceTunnelSocketTest {
    private fun tls(block: (MockWebServer, OkHttpClient, CoroutineScope) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            block(server, client, scope)
        } } finally { scope.cancel() }
    }
    @Test fun socketReadTimeoutIsEnforcedWhenTheServiceDoesNotRespond() = tls { server, client, scope ->
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})); server.start()
        ServiceTunnelSocket(client, server.url("/api/service-browser/s/stream"), scope) { "renewed" }.use { socket ->
            socket.connect(InetSocketAddress.createUnresolved("service.invalid", 80), 3000)
            socket.soTimeout = 150
            assertThrows(java.net.SocketTimeoutException::class.java) { socket.getInputStream().read() }
        }
    }
    @Test fun closingTheTunnelPreservesAllResponsePacketsBeforeEof() = tls { server, client, scope ->
        val expected = ByteArray(90_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(expected.copyOfRange(0, 45_000).toByteString())
                webSocket.send(expected.copyOfRange(45_000, 90_000).toByteString())
                webSocket.close(1000, "done")
            }
        })); server.start()
        ServiceTunnelSocket(client, server.url("/api/service-browser/s/stream"), scope) { "renewed" }.use { socket ->
            socket.connect(InetSocketAddress.createUnresolved("service.invalid", 80), 3000)
            socket.getOutputStream().write("request".toByteArray())
            assertArrayEquals(expected, socket.getInputStream().readBytes())
        }
    }
    @Test fun carriesHttpBytesInsideTlsWebsocketWithoutCredentialsInPayload() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    assertFalse(bytes.utf8().contains("X-Bot4VPS-Mobile-Session"))
                    webSocket.send("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".encodeUtf8())
                }
            }))
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            ServiceTunnelSocket(client, server.url("/api/service-browser/s/stream"), scope) { "renewed" }.use { socket ->
                socket.connect(InetSocketAddress.createUnresolved("service.invalid", 80), 3000)
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: service\r\n\r\n".toByteArray())
                val response = readHead(socket.getInputStream())
                assertEquals("HTTP/1.1 200 OK", response.first)
                val body = ByteArray(5); java.io.DataInputStream(socket.getInputStream()).readFully(body)
                assertEquals("hello", String(body))
            }
        }
        scope.cancel()
    }
}
