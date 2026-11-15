package com.bot4vps.mobile.data.api

import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

class MobileAddressChangeRequired(val change: com.bot4vps.mobile.data.model.MobileConnectionChange) : java.io.IOException(
    "Это код смены адреса. Откройте «Изменить адрес» у сохранённого сервиса: повторная привязка не требуется."
)

/** Both scanners in the connection dialog use the payload type, not the button
 * from which the scanner was opened. Address changes always require preview. */
internal suspend fun routePanelConnectionCode(value: String, pair: suspend (String) -> Unit,
    preview: suspend (String) -> Unit, allowPairing: Boolean = true) {
    val entered = value.trim()
    try { readMobileInvitation(entered) ?: throw MobilePairingRequired("Нужен полный код b4vps:… из веб-панели") }
    catch (_: MobileAddressChangeRequired) { preview(entered); return }
    check(allowPairing) { "Это QR привязки телефона. Для смены адреса нужен QR из окна изменения Mobile API." }
    pair(entered)
}

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
        val action = data["action"]?.jsonPrimitive?.contentOrNull
        if (action == "connection_change") throw MobileAddressChangeRequired(com.bot4vps.mobile.data.model.connectionChange(data))
        require(action == null || action == "pair")
        val url = data.getValue("url").jsonPrimitive.content
        val parsed = PanelUrl.endpoint(url, "/api/mobile/pair")
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty())
        val code = data.getValue("code").jsonPrimitive.content
        val pin = data.getValue("server_pin").jsonPrimitive.content
        require(code.length in 40..64 && pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32)
        return MobilePairingInvitation(mobileOrigin(parsed), code, pin)
    } catch (change: MobileAddressChangeRequired) { throw change
    } catch (_: Exception) {
        throw MobilePairingRequired("Код привязки повреждён. Скопируйте новый код целиком из веб-панели.")
    }
}
