package com.bot4vps.mobile.ui.browser

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.TimeUnit

class ServiceBrowserProxyTest {
    @Test fun successfulServiceLogoutClosesOnlyItsBrowserLifetimeAfterForwardingTheRequest() {
        MockWebServer().use { upstream ->
            upstream.start()
            val count = java.util.concurrent.atomic.AtomicInteger()
            val target = BrowserTarget("session", upstream.url("/").toString().removeSuffix("/"), "/", "https://panel.example/api/service-browser/session/stream".toHttpUrl(), "ssclash")
            ServiceBrowserProxy(target, logout = { count.incrementAndGet() }) { object : Socket() {
                override fun connect(address: SocketAddress?, timeout: Int) = super.connect(InetSocketAddress("127.0.0.1", upstream.port), timeout)
            } }.use { proxy ->
                val client = OkHttpClient.Builder().followRedirects(false).readTimeout(3, TimeUnit.SECONDS).build()
                val cookie = client.newCall(Request.Builder().url(proxy.startUrl).build()).execute().use { it.header("Set-Cookie")!!.substringBefore(';') }
                upstream.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/login"))
                client.newCall(Request.Builder().url(proxy.origin + "/logout").header("Cookie", "$cookie; session=own")
                    .post(FormBody.Builder().add("csrf", "own-token").build()).build()).execute().use { assertEquals(303, it.code) }
                val request = upstream.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method); assertEquals("/logout", request.path)
                assertEquals("session=own", request.getHeader("Cookie")); assertEquals("csrf=own-token", request.body.readUtf8())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (count.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(1, count.get())
            }
        }
    }
    private fun test(service: String = "", block: (ServiceBrowserProxy, MockWebServer, OkHttpClient, String) -> Unit) {
        MockWebServer().use { upstream ->
            upstream.start()
            val target = BrowserTarget("session", upstream.url("/").toString().removeSuffix("/"), "/login", "https://panel.example/api/service-browser/session/stream".toHttpUrl(), service)
            ServiceBrowserProxy(target) { object : Socket() {
                override fun connect(address: SocketAddress?, timeout: Int) = super.connect(InetSocketAddress("127.0.0.1", upstream.port), timeout)
            } }.use { proxy ->
                val client = OkHttpClient.Builder().followRedirects(false).readTimeout(3, TimeUnit.SECONDS).build()
                val cookie = client.newCall(Request.Builder().url(proxy.startUrl).build()).execute().use { response ->
                    assertEquals(302, response.code); assertEquals("/login", response.header("Location"))
                    response.header("Set-Cookie")!!.substringBefore(';')
                }
                block(proxy, upstream, client, cookie)
            }
        }
    }
    @Test fun forwardsLoginBodyAndServiceCookieWithoutLocalCookie() = test { proxy, upstream, client, cookie ->
        upstream.enqueue(MockResponse().setResponseCode(302).addHeader("Location", upstream.url("/home")).addHeader("Set-Cookie", "service=ok; Domain=example.com; Path=/; HttpOnly"))
        val request = Request.Builder().url(proxy.origin + "/login?next=%2Fhome")
            .header("Cookie", "$cookie; service=old").header("Origin", proxy.origin)
            .post(FormBody.Builder().add("username", "owner").add("password", "secret").build()).build()
        client.newCall(request).execute().use { response ->
            assertEquals(302, response.code); assertEquals(proxy.origin + "/home", response.header("Location"))
            assertFalse(response.header("Set-Cookie")!!.contains("Domain", true))
        }
        val received = upstream.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/login?next=%2Fhome", received.path)
        assertEquals("service=old", received.getHeader("Cookie"))
        assertEquals(proxy.target.origin, received.getHeader("Origin"))
        assertNull(received.getHeader("X-Bot4VPS-Mobile-Session"))
        assertEquals("username=owner&password=secret", received.body.readUtf8())
    }
    @Test fun rewritesAbsoluteHtmlLinksAndPreservesRelativeApiPaths() = test { proxy, upstream, client, cookie ->
        upstream.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").addHeader("Referrer-Policy", "no-referrer").setBody("<a href=\"${proxy.target.origin}/home\">Home</a><script>fetch('/api/login')</script>"))
        client.newCall(Request.Builder().url(proxy.origin + "/").header("Cookie", cookie).build()).execute().use { response ->
            assertEquals(200, response.code)
            val html = response.body!!.string()
            assertTrue(html.contains(proxy.origin + "/home")); assertTrue(html.contains("fetch('/api/login')"))
            assertEquals("same-origin", response.header("Referrer-Policy"))
        }
    }
    @Test fun loginRedirectAndCompressedAssetsReuseTheServiceConnection() = test { proxy, upstream, client, cookie ->
        upstream.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/home").setHeader("Set-Cookie", "session=signed; Path=/; HttpOnly; SameSite=Lax"))
        client.newCall(Request.Builder().url(proxy.origin + "/login").header("Cookie", "$cookie; csrf=token")
            .header("Origin", proxy.origin).post(FormBody.Builder().add("csrf", "token").add("password", "test-password").build()).build()).execute().use {
            assertEquals(303, it.code); assertEquals("/home", it.header("Location"))
        }
        val login = upstream.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("csrf=token&password=test-password", login.body.readUtf8())
        val page = "<html>${proxy.target.origin}/api</html>".repeat(3000)
        val compressed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(page.toByteArray()) } }.toByteArray()
        assertTrue(compressed.size < page.length / 10)
        repeat(18) { index ->
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setHeader("Content-Encoding", "gzip").setBody(okio.Buffer().write(compressed)))
            client.newCall(Request.Builder().url(proxy.origin + "/home?asset=$index").header("Cookie", "$cookie; session=signed").build()).execute().use {
                assertEquals(200, it.code); assertEquals(page.replace(proxy.target.origin, proxy.origin), it.body!!.string())
                assertNull(it.header("Content-Encoding"))
            }
            val request = upstream.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals(index + 1, request.sequenceNumber)
            assertEquals("gzip", request.getHeader("Accept-Encoding"))
            assertEquals("session=signed", request.getHeader("Cookie"))
        }
    }
    @Test fun refusesUnauthenticatedLocalRequests() = test { proxy, upstream, client, _ ->
        client.newCall(Request.Builder().url(proxy.origin + "/api/login").build()).execute().use { assertEquals(502, it.code) }
        assertNull(upstream.takeRequest(200, TimeUnit.MILLISECONDS))
    }
    @Test fun refusesCrossOriginRequestsEvenWithSessionCookie() = test { proxy, upstream, client, cookie ->
        client.newCall(Request.Builder().url(proxy.origin + "/api/login").header("Cookie", cookie).header("Origin", "https://other.example").build()).execute().use { assertEquals(502, it.code) }
        assertNull(upstream.takeRequest(200, TimeUnit.MILLISECONDS))
    }
    @Test fun rejectsOpaqueFormOriginsEvenWhenTheyHaveASessionCookie() = test { proxy, upstream, client, cookie ->
        client.newCall(Request.Builder().url(proxy.origin + "/login").header("Cookie", cookie).header("Origin", "null").post(FormBody.Builder().add("password", "test").build()).build()).execute().use { assertEquals(502, it.code) }
        assertNull(upstream.takeRequest(200, TimeUnit.MILLISECONDS))
    }
    @Test fun binaryResponsesAreNotModified() = test { proxy, upstream, client, cookie ->
        val bytes = byteArrayOf(0, 1, -1, 10, 13)
        upstream.enqueue(MockResponse().addHeader("Content-Type", "application/octet-stream").setBody(okio.Buffer().write(bytes)))
        client.newCall(Request.Builder().url(proxy.origin + "/file").header("Cookie", cookie).build()).execute().use { assertArrayEquals(bytes, it.body!!.bytes()) }
    }
    @Test fun compressedSsclashStylesheetKeepsSourceAndGetsDrawerCompatibilityWithCorrectLength() = test("ssclash") { proxy, upstream, client, cookie ->
        val css = ".sidebar { background: white; }"
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(buffer).use { it.write(css.toByteArray()) }
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/css").setHeader("Content-Encoding", "gzip")
            .setBody(okio.Buffer().write(buffer.toByteArray())))
        client.newCall(Request.Builder().url(proxy.origin + "/static/css/main.css").header("Cookie", cookie).build()).execute().use { response ->
            assertEquals(200, response.code)
            val bytes = response.body!!.bytes()
            assertEquals(serviceContent("ssclash", "/static/css/main.css", "text/css", css), String(bytes))
            assertEquals(bytes.size.toString(), response.header("Content-Length"))
            assertNull(response.header("Content-Encoding"))
        }
    }
    @Test fun chunkedBodyParsingRejectsConflictingFraming() {
        assertThrows(IllegalArgumentException::class.java) { readBody("test".byteInputStream(), listOf("Content-Length" to "4", "Transfer-Encoding" to "chunked")) }
    }
    @Test fun decodesChunkedRequestUploads() {
        assertEquals("hello", String(readBody("2\r\nhe\r\n3\r\nllo\r\n0\r\n\r\n".byteInputStream(), listOf("Transfer-Encoding" to "chunked"))))
    }
}
