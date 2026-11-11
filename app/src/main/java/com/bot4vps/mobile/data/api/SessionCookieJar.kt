package com.bot4vps.mobile.data.api

import com.bot4vps.mobile.data.local.SecretStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class SavedCookie(val origin: String, val value: String)

@Singleton
class SessionCookieJar @Inject constructor(private val secrets: SecretStorage) : CookieJar {
    private val codec = Json { ignoreUnknownKeys = true }
    private var records: MutableList<SavedCookie>? = null

    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
    private fun entries(): MutableList<SavedCookie> = records ?: run {
        val restored = secrets.read()?.let { runCatching { codec.decodeFromString<List<SavedCookie>>(it) }.getOrNull() }
        (restored ?: emptyList()).toMutableList().also { records = it }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val stored = entries()
        cookies.forEach { cookie ->
            stored.removeAll { record ->
                record.origin == origin(url) && Cookie.parse(url, record.value)?.let {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                } == true
            }
            if (cookie.expiresAt > System.currentTimeMillis()) stored.add(SavedCookie(origin(url), cookie.toString()))
        }
        secrets.write(codec.encodeToString(stored))
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = entries()
        .filter { it.origin == origin(url) }
        .mapNotNull { Cookie.parse(url, it.value) }
        .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }

    @Synchronized
    fun clear() {
        records = mutableListOf()
        secrets.write(null)
    }
}
