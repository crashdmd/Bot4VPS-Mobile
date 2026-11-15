package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class ManagedPhone(val id: String, val name: String, val created: Double?, val isPrimary: Boolean)
data class PhoneInvitation(val code: String, val qr: String, val expires: Double, val statusToken: String)

@Singleton
class PhoneManagementRepository @Inject constructor(private val panel: NativePanelClient, private val mobile: MobileAccessClient) {
    suspend fun phones(origin: String): List<ManagedPhone> = panel.request("GET", "/api/mobile/security/devices", baseUrl = origin).jsonObject
        .objects("devices").map { ManagedPhone(it.getValue("id").jsonPrimitive.content, it.text("name") ?: "Телефон", it["created"]?.jsonPrimitive?.doubleOrNull, it.flag("is_primary")) }
    private suspend fun execute(origin: String, operation: String, target: String = "", name: String = "Телефон"): JsonObject {
        val challenge = panel.request("POST", "/api/mobile/security/devices/challenge", baseUrl = origin, body = buildJsonObject {
            put("operation", operation); put("target_device_id", target); put("name", name)
        }).jsonObject
        require(challenge.text("operation") == operation && challenge.text("target_device_id") == target) { "Сервис вернул подтверждение другой операции" }
        if (operation == "pair") require(challenge.text("name") == name.trim().take(80).ifBlank { "Телефон" }) { "Имя нового телефона изменилось" }
        val id = challenge.getValue("id").jsonPrimitive.content
        val nonce = challenge.getValue("nonce").jsonPrimitive.content
        val signature = withContext(Dispatchers.IO) { mobile.signAuthDecision(PanelUrl.endpoint(origin, "/api/mobile/security/devices/confirm"), id, nonce, "approve") }
        return panel.request("POST", "/api/mobile/security/devices/confirm", baseUrl = origin, body = buildJsonObject { put("id", id); put("signature", signature) }).jsonObject
    }
    suspend fun invite(origin: String, name: String): PhoneInvitation {
        val result = execute(origin, "pair", name = name)
        check(result.flag("ok")) { "Сервис не подтвердил создание QR" }
        val code = result.getValue("code").jsonPrimitive.content
        val invitation = readMobileInvitation(code) ?: error("Сервис вернул неверный код привязки")
        check(mobileOrigin(PanelUrl.endpoint(invitation.url, "/api/me")) == origin) { "QR относится к другому сервису" }
        return PhoneInvitation(code, result.getValue("qr").jsonPrimitive.content, result.getValue("expires").jsonPrimitive.double,
            result.getValue("status_token").jsonPrimitive.content)
    }
    suspend fun invitationStatus(origin: String, token: String): String = panel.request("POST", "/api/mobile/security/devices/pair/status", baseUrl = origin,
        body = buildJsonObject { put("status_token", token) }).jsonObject.text("state") ?: error("Нет состояния привязки")
    suspend fun revoke(origin: String, target: String) {
        val result = execute(origin, "revoke", target)
        check(result.flag("ok") && result.text("device_id") == target) { "Сервис не подтвердил отзыв доступа. Телефон остаётся в списке." }
    }
}
