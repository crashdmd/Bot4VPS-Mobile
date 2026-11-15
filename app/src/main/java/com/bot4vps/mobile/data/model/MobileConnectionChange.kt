package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.*
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class MobileConnectionChange(val url: String, val oldUrl: String, val id: String, val nonce: String, val serverPin: String,
    val deviceId: String = "", val approved: Boolean = false,
    val localOldUrl: String? = null, val profileOldUrl: String? = null) {
    val origin get() = mobileOrigin(PanelUrl.endpoint(url, "/api/me"))
    val oldOrigin get() = mobileOrigin(PanelUrl.endpoint(oldUrl, "/api/me"))
    val sourceOrigin get() = mobileOrigin(PanelUrl.endpoint(localOldUrl ?: oldUrl, "/api/me"))
    val profileOrigin get() = mobileOrigin(PanelUrl.endpoint(profileOldUrl ?: oldUrl, "/api/me"))
}

fun connectionUrl(value: String): String {
    val url = PanelUrl.normalize(value)
    require(PanelUrl.endpoint(url, "/api/me").encodedPath == "/api/me") { "Введите адрес Mobile API без пути" }
    // PanelUrl forbids HTTP, userinfo, query and fragment. Reject path separately.
    val parsed = value.toHttpUrl()
    require(parsed.encodedPath == "/") { "Введите адрес Mobile API без пути" }
    return mobileOrigin(parsed)
}

fun manualConnectionUrl(value: String): String {
    val entered = value.trim()
    require(entered.isNotBlank()) { "Введите IP или домен сервиса" }
    return connectionUrl(if (entered.contains("://")) entered else "https://$entered")
}

fun connectionChange(data: JsonObject): MobileConnectionChange {
    require(data["version"]?.jsonPrimitive?.intOrNull == 1 && data["action"]?.jsonPrimitive?.content == "connection_change") { "Это не QR смены адреса Mobile API" }
    fun field(name: String) = data.getValue(name).jsonPrimitive.content.also {
        require(it.isNotBlank() && it.length <= 128 && it.all { c -> c.code in 33..126 }) { "Некорректные данные смены адреса" }
    }
    val url = data.getValue("url").jsonPrimitive.content
    val old = data.getValue("old_url").jsonPrimitive.content
    connectionUrl(url); connectionUrl(old)
    require(url.all { it.code in 33..126 }) { "Некорректный адрес" }
    val pin = data.getValue("server_pin").jsonPrimitive.content
    require(pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32) { "Некорректный отпечаток сервиса" }
    return MobileConnectionChange(url, old, field("id"), field("nonce"), pin)
}

fun readConnectionChange(value: String): MobileConnectionChange {
    require(value.startsWith("b4vps:") && value.length <= 8192) { "Это не QR смены адреса Mobile API" }
    return connectionChange(Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value.removePrefix("b4vps:")), Charsets.UTF_8)).jsonObject)
}

fun connectionChangeMessage(change: MobileConnectionChange, deviceId: String): ByteArray {
    require(listOf(change.id, change.nonce, deviceId).all { it.isNotBlank() && it.length <= 128 && it.all { c -> c.code in 33..126 } })
    require(change.url.all { it.code in 33..126 })
    return "bot4vps-mobile-connection-v1\n${change.id}\n${change.nonce}\n$deviceId\n${change.url}".toByteArray(Charsets.US_ASCII)
}
