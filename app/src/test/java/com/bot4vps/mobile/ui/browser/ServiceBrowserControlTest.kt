package com.bot4vps.mobile.ui.browser

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ServiceBrowserControlTest {
    @Test fun recoveryAllowsTheFullMinuteAndEndsAtSixtySecondsWithoutAReply() {
        var now = 0L
        val grace = BrowserRecovery({ now })
        now = 59_999; assertEquals(1, grace.remaining())
        now = 60_000; assertEquals(0, grace.remaining())
        grace.reply(); assertEquals(60_000, grace.remaining())
        now += 10_000; assertEquals(50_000, grace.remaining())
    }
    @Test fun aCachedPanelStatusDoesNotRestartTheGatewayRecoveryMinute() {
        var now = 10_000L
        val grace = BrowserRecovery({ now })
        grace.serverRemaining(20_000); assertEquals(20_000, grace.remaining())
        now += 5000; assertEquals(15_000, grace.remaining())
        grace.serverRemaining(15_000); now += 15_000; assertEquals(0, grace.remaining())
        grace.serverRemaining(120_000); assertEquals(60_000, grace.remaining())
    }
    @Test fun controlCarriesOnlyWindowSignalsAndClosesWithoutReconnecting() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val signals = CopyOnWriteArrayList<String>()
        val ended = AtomicInteger()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"type\":\"browser_status\",\"gateway_available\":true,\"retry_millis\":60000}")
                }
                override fun onMessage(webSocket: WebSocket, text: String) { signals.add(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }))
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            val control = ServiceBrowserControl(client, server.url("/api/service-browser/s/control"), { "native-secret" }, { _, _ -> }, { ended.incrementAndGet() })
            withTimeout(3000) { withContext(Dispatchers.IO) { control.awaitGateway() } }
            control.close()
            withTimeout(3000) { while (signals.none { it.contains("browser_close") }) delay(10) }
            assertEquals(1, server.requestCount)
            assertEquals(0, ended.get())
            assertTrue(signals.none { it.contains("native-secret") || it.contains("Mobile-Session") })
        }
    }
    @Test fun logoutPathsAreLimitedToTheRegisteredService() {
        assertTrue(serviceLogout("ssclash", "/logout"))
        assertTrue(serviceLogout("ssclash", "/%6Cogout?csrf=token"))
        assertTrue(serviceLogout("adguardhome", "/control/logout"))
        assertTrue(serviceLogout("luci", "/cgi-bin/luci/;stok=token/admin/logout"))
        assertTrue(serviceLogout("podkop", "/cgi-bin/luci/admin/logout"))
        assertTrue(serviceLogout("3x-ui", "/private-panel/logout"))
        assertFalse(serviceLogout("adguardhome", "/logout"))
        assertFalse(serviceLogout("ssclash", "/api/logout.js"))
        assertFalse(serviceLogout("luci", "/cgi-bin/luci/admin/login"))
    }
}
