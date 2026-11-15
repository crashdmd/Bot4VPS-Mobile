package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.PanelUrl
import com.bot4vps.mobile.data.api.mobileOrigin
import java.util.Base64
import kotlinx.serialization.json.*

data class MobileLoginQr(val origin: String, val serverPin: String, val id: String, val nonce: String)

private fun authField(value: String): String {
    require(value.isNotBlank() && value.length <= 128 && value.all { it.code in 33..126 }) { "Неверные данные подтверждения" }
    return value
}

fun authDecisionMessage(id: String, nonce: String, deviceId: String, action: String): ByteArray {
    require(action in setOf("approve", "reject", "cancel"))
    return "bot4vps-auth-v1\n${authField(id)}\n${authField(nonce)}\n${authField(deviceId)}\n$action".toByteArray(Charsets.US_ASCII)
}

fun readLoginQr(value: String): MobileLoginQr {
    require(value.startsWith("b4vps-auth:") && value.length <= 4096) { "Это не QR-код входа Bot4VPS" }
    val data = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value.removePrefix("b4vps-auth:")), Charsets.UTF_8)).jsonObject
    require(data["version"]?.jsonPrimitive?.intOrNull == 1 && data["action"]?.jsonPrimitive?.content == "login") { "Версия QR-кода не поддерживается" }
    val url = PanelUrl.endpoint(data.getValue("url").jsonPrimitive.content, "/api/mobile/security/decision")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "QR должен содержать HTTPS-адрес" }
    val pin = data.getValue("server_pin").jsonPrimitive.content
    require(pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32) { "Неверный отпечаток панели" }
    return MobileLoginQr(mobileOrigin(url), pin, authField(data.getValue("id").jsonPrimitive.content), authField(data.getValue("nonce").jsonPrimitive.content))
}

data class SecurityRequest(
    val id: String, val purpose: String = "", val event: String = "", val nonce: String = "",
    val method: String = "", val state: String = "", val agent: String = "", val ip: String = "", val username: String = "",
    val started: Double? = null, val deadline: Double? = null, val remaining: Long = 0,
    val expires: Double? = null, val actions: List<String>? = null,
    val targetDeviceId: String = "", val targetName: String = "", val message: String = ""
) {
    val informational get() = event.isNotBlank()
}

fun securityRequest(value: JsonObject): SecurityRequest = SecurityRequest(
    id = value.getValue("id").jsonPrimitive.content,
    purpose = value.text("purpose").orEmpty(), event = value.text("event").orEmpty(), nonce = value.text("nonce").orEmpty(),
    method = value.text("method").orEmpty(), state = value.text("state").orEmpty(), agent = value.text("agent").orEmpty(), ip = value.text("ip").orEmpty(), username = value.text("username").orEmpty(),
    started = value["started_at"]?.jsonPrimitive?.doubleOrNull ?: value["created"]?.jsonPrimitive?.doubleOrNull,
    deadline = value["deadline"]?.jsonPrimitive?.doubleOrNull, remaining = value["remaining"]?.jsonPrimitive?.longOrNull ?: 0,
    expires = value["expires"]?.jsonPrimitive?.doubleOrNull,
    actions = (value["mobile_actions"] as? JsonArray)?.map { it.jsonPrimitive.content },
    targetDeviceId = value.text("target_device_id").orEmpty(), targetName = value.text("target_name").orEmpty(), message = phoneRoleMessage(value.text("message").orEmpty())
)

fun securityActions(request: SecurityRequest, serverNow: Double): List<String> {
    if (request.informational) return emptyList()
    val allowed = when (request.purpose) {
        "login", "protection_disable", "two_factor_enable", "two_factor_disable", "primary_transfer" -> if (request.expires != null && request.expires <= serverNow) emptyList() else listOf("approve", "reject")
        "unlink", "unlink_other" -> when (request.state) {
            "phone" -> listOf("approve", "reject")
            "code" -> listOf("cancel")
            "waiting", "ready" -> if (request.method == "delayed" && (request.deadline == null || request.deadline > serverNow)) listOf("cancel") else listOf("approve", "cancel")
            else -> emptyList()
        }
        else -> emptyList()
    }
    return request.actions?.let { server -> allowed.filter { it in server } } ?: allowed
}

fun securityPromptKey(origin: String, id: String) = "$origin\n$id"

/** Entering a Web code is an intermediate step; waiting after a valid code is actionable. */
fun visibleSecurityRequests(requests: List<SecurityRequest>, now: Double): List<SecurityRequest> = requests.filter { request ->
    if (request.informational) request.event !in setOf("unlink_started", "unlink_waiting", "unlink_ready")
    else !(request.purpose in setOf("unlink", "unlink_other") && request.state == "code") && securityActions(request, now).isNotEmpty()
}

fun securityTitle(request: SecurityRequest): String = when {
    request.purpose == "login" -> "Запрос входа в панель"
    request.purpose == "protection_disable" -> "Выключение защиты от отвязки"
    request.purpose == "two_factor_enable" -> "Включение 2FA"
    request.purpose == "two_factor_disable" -> "Отключение 2FA"
    request.purpose == "unlink" -> "Отвязка этого телефона"
    request.purpose == "unlink_other" -> "Отзыв доступа другого телефона"
    request.purpose == "primary_transfer" -> "Назначение основным телефоном"
    else -> when (request.event) {
        "new_login" -> "Вход с нового IP"
        "device_connected" -> "Подключён новый телефон"
        "device_unlinked" -> "Телефон отвязан"
        "primary_assigned" -> "Телефон назначен основным"
        "primary_selection_required" -> "Выберите основной телефон"
        "unlink_requested" -> "Запрошен отзыв доступа"
        "two_factor_telegram" -> "Второй фактор переключён на Telegram"
        "two_factor_disabled" -> "2FA отключена"
        "unlink_started" -> "Начата отвязка телефона"
        "unlink_waiting" -> "Ожидание отвязки телефона"
        "unlink_cancelled" -> "Отвязка отменена"
        "unlink_ready" -> "Ожидание отвязки завершено"
        else -> "Уведомление безопасности"
    }
}
