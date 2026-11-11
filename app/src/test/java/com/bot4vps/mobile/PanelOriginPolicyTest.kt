package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.panelOriginClient
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PanelOriginPolicyTest {
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private fun server() = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        start()
    }
    private fun transport(): OkHttpClient {
        val tls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
    }
    @Test fun chosenHttpsOriginWorksButOtherPortReceivesNoCredentialsOrBody() {
        val chosen = server(); val other = server()
        try {
            val client = panelOriginClient(transport(), chosen.url("/"))
            chosen.enqueue(MockResponse().setBody("{}"))
            client.newCall(Request.Builder().url(chosen.url("/api/action"))
                .header("X-Bot4VPS-Mobile-Session", "secret").post(RequestBody.create(null, "payload")).build()).execute().close()
            assertEquals("payload", chosen.takeRequest().body.readUtf8())
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(other.url("/api/action"))
                    .header("X-Bot4VPS-Mobile-Session", "secret").post(RequestBody.create(null, "payload")).build()).execute().close()
            }
            assertEquals(0, other.requestCount)
        } finally { chosen.shutdown(); other.shutdown() }
    }
    @Test fun redirectDoesNotForwardTokenOrPayloadToAnotherServer() {
        val chosen = server(); val other = server()
        try {
            chosen.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/action")))
            val client = panelOriginClient(transport().newBuilder().followRedirects(true).build(), chosen.url("/"))
            client.newCall(Request.Builder().url(chosen.url("/api/action"))
                .header("X-Bot4VPS-Mobile-Session", "secret").post(RequestBody.create(null, "payload")).build()).execute().use { assertEquals(307, it.code) }
            assertEquals(0, other.requestCount)
        } finally { chosen.shutdown(); other.shutdown() }
    }
    @Test fun httpsClientRejectsDowngradeBeforeSendingRequest() {
        val server = server()
        try {
            val client = panelOriginClient(transport(), server.url("/"))
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/api/me").newBuilder().scheme("http").build()).build()).execute().close() }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun aLaterInterceptorCannotRewriteDestination() {
        val chosen = server(); val other = server()
        try {
            val base = transport().newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(other.url("/api/action")).build()) }.build()
            val client = panelOriginClient(base, chosen.url("/"))
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(chosen.url("/api/action")).build()).execute().close() }
            assertEquals(0, other.requestCount)
        } finally { chosen.shutdown(); other.shutdown() }
    }
    @Test fun httpCannotBeSelectedEvenInsideLan() {
        val server = server()
        try {
            assertThrows(IOException::class.java) { panelOriginClient(transport(), server.url("/").newBuilder().scheme("http").build()) }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
