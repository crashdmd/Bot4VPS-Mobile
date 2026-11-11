package com.bot4vps.mobile.data.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Trust only the exact server key supplied by private Web pairing.
 * An unrelated self-signed or CA-issued certificate never satisfies this pin.
 */
fun pinnedMobileClient(base: OkHttpClient, url: HttpUrl, pin: String): OkHttpClient {
    require(url.isHttps)
    val expected = mobileOrigin(url)
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificate not supported") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
            leaf.checkValidity()
            val actual = "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded))
            if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw CertificateException("Ключ сервера изменился. Проверьте адрес и привяжите телефон через Web-панель.")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return base.newBuilder().sslSocketFactory(context.socketFactory, trust)
        // The invitation authenticates the key and origin together; public CA
        // hostname validation is unnecessary for this private server identity.
        .hostnameVerifier { host, _ -> host == url.host }
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            if (mobileOrigin(chain.request().url) != expected) throw MobilePairingRequired("Ключ сервера действует только для адреса из кода привязки")
            chain.proceed(chain.request())
        }.build()
}
