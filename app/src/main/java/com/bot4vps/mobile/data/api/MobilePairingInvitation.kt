package com.bot4vps.mobile.data.api

import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

data class MobilePairingInvitation(val url: String, val code: String, val serverPin: String)

fun mobileOrigin(url: HttpUrl): String {
    val host = if (':' in url.host) "[${url.host}]" else url.host
    return "${url.scheme}://$host:${url.port}"
}

fun readMobileInvitation(value: String): MobilePairingInvitation? {
    if (!value.startsWith("b4vps:")) return null
    try {
        require(value.length <= 4096)
        val data = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value.removePrefix("b4vps:")), Charsets.UTF_8)).jsonObject
        require(data["version"]?.jsonPrimitive?.intOrNull == 1)
        val url = data.getValue("url").jsonPrimitive.content
        val parsed = PanelUrl.endpoint(url, "/api/mobile/pair")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty())
        val code = data.getValue("code").jsonPrimitive.content
        val pin = data.getValue("server_pin").jsonPrimitive.content
        require(code.length in 40..64 && pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32)
        return MobilePairingInvitation(mobileOrigin(parsed), code, pin)
    } catch (_: Exception) {
        throw MobilePairingRequired("Код привязки повреждён. Скопируйте новый код целиком из веб-панели.")
    }
}
