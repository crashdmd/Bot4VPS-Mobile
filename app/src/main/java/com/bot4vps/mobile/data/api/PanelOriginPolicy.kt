package com.bot4vps.mobile.data.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Runtime origin policy for user-selected panels, including arbitrary local IP/DNS.
 * Check before auth/cookies and again before writing any network request.
 */
fun panelOriginClient(base: OkHttpClient, selected: HttpUrl): OkHttpClient {
    if (!selected.isHttps) throw MobilePairingRequired("Для мобильного API требуется HTTPS")
    val guard = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val target = chain.request().url
            if (target.scheme != selected.scheme || target.host != selected.host || target.port != selected.port) {
                throw MobilePairingRequired("Запрос разрешён только по выбранному адресу панели. Измените адрес в настройках подключения.")
            }
            return chain.proceed(chain.request())
        }
    }
    return base.newBuilder().apply {
        interceptors().add(0, guard)
        addNetworkInterceptor(guard)
        followRedirects(false)
        followSslRedirects(false)
    }.build()
}
