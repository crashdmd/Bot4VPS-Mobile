package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class SecuritySnapshot(
    val origin: String = "", val supported: Boolean? = null, val requests: List<SecurityRequest> = emptyList(),
    val serverTime: Double = System.currentTimeMillis() / 1000.0, val receivedAt: Long = System.nanoTime(),
    val totpRegistered: Boolean = false, val twoFactorEnabled: Boolean = false,
    val isPrimary: Boolean = false, val primaryDeviceId: String? = null, val deviceCount: Int? = null,
    val totpSetupAllowed: Boolean = false, val twoFactorChannel: String? = null,
    val unlinkProtected: Boolean = false, val totpGeneration: String? = null, val error: String? = null,
    val receiptSupported: Boolean = false, val receivedRequestIds: Set<String> = emptySet(),
    val totpRemovalRequestIds: List<String> = emptyList()
) {
    fun now(): Double = serverTime + (System.nanoTime() - receivedAt) / 1_000_000_000.0
}

@Singleton
class SecurityRepository @Inject constructor(
    private val panel: NativePanelClient, private val mobile: MobileAccessClient, private val prefs: PreferencesManager,
    private val authenticator: com.bot4vps.mobile.data.local.AuthenticatorStorage
) {
    private val mutable = MutableStateFlow(SecuritySnapshot())
    val state = mutable.asStateFlow()
    private val pairedInbox = PairedSecurityInbox(prefs.profiles, afterDelivery = ::afterDelivery, fetch = ::fetchFor)
    val inboxes = pairedInbox.state
    private val refreshLock = Mutex()

    suspend fun refresh(): SecuritySnapshot = refreshLock.withLock {
        val base = prefs.baseUrl.first() ?: return@withLock SecuritySnapshot()
        val origin = mobileOrigin(PanelUrl.endpoint(base, "/api/mobile/security"))
        if (mutable.value.origin != origin) mutable.value = SecuritySnapshot(origin = origin)
        val snapshot = refreshFor(origin)
        if (prefs.baseUrl.first() == base) mutable.value = snapshot
        snapshot
    }

    suspend fun refreshFor(origin: String): SecuritySnapshot = pairedInbox.refresh(origin)

    private suspend fun fetchFor(origin: String): SecuritySnapshot {
        try {
            val data = panel.request("GET", "/api/mobile/security", baseUrl = origin).jsonObject
            val removed = (data["totp_removed_generations"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                .filter { it.matches(Regex("[0-9a-f]{24}")) }.toSet()
            if (removed.isNotEmpty()) authenticator.removeDisabledPanel(origin, removed)
            val cleanup = data.objects("requests").filter { it.text("event") == "totp_removed" }
            cleanup.forEach { request ->
                request.text("totp_generation")?.let { generation -> authenticator.removeDisabledPanel(origin, setOf(generation)) }
            }
            val snapshot = SecuritySnapshot(origin = origin, supported = true,
                requests = data.objects("requests").filter { it.text("event") != "totp_removed" }.mapNotNull { runCatching { securityRequest(it) }.getOrNull() },
                serverTime = data["server_time"]?.jsonPrimitive?.doubleOrNull ?: System.currentTimeMillis() / 1000.0,
                totpRegistered = data.flag("totp_registered"), twoFactorEnabled = data.flag("two_factor_enabled"),
                isPrimary = data.flag("is_primary"), primaryDeviceId = data.text("primary_device_id"),
                deviceCount = data["device_count"]?.jsonPrimitive?.intOrNull,
                totpSetupAllowed = data.flag("totp_setup_allowed"), twoFactorChannel = data.text("two_factor_channel"),
                unlinkProtected = data.flag("unlink_protected"), totpGeneration = data.text("totp_generation"),
                receiptSupported = data.flag("receipt_supported"),
                receivedRequestIds = data.objects("requests").filter { it["received_at"]?.jsonPrimitive?.doubleOrNull != null }
                    .mapNotNull { it.text("id") }.toSet(),
                totpRemovalRequestIds = cleanup.mapNotNull { it.text("id") })
            return snapshot
        } catch (error: CancellationException) { throw error }
        catch (error: ApiError) {
            if (error.status == 404) {
                val snapshot = SecuritySnapshot(origin = origin, supported = false)
                return snapshot
            } else throw error
        }
    }

    private suspend fun afterDelivery(snapshot: SecuritySnapshot) {
        acknowledgePrimaryRequests(snapshot) { id, nonce ->
            panel.request("POST", "/api/mobile/security/received", baseUrl = snapshot.origin,
                body = buildJsonObject { put("id", id); put("nonce", nonce) })
        }
        snapshot.totpRemovalRequestIds.forEach { id ->
            try {
                panel.request("POST", "/api/mobile/security/ack", baseUrl = snapshot.origin,
                    body = buildJsonObject { put("id", id) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Local cleanup succeeded; retry its receipt on the next poll. */ }
        }
    }

    suspend fun pollAll(interval: Long = 5000) = pairedInbox.poll(interval)

    suspend fun poll(interval: Long = 2000) {
        var delayMillis = interval
        while (currentCoroutineContext().isActive) {
            try { refresh(); delayMillis = interval }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.update { it.copy(error = errorText(error)) }
                delayMillis = (delayMillis * 2).coerceAtMost(30_000)
            }
            delay(delayMillis)
        }
    }

    suspend fun loginPreview(qr: MobileLoginQr): SecurityRequest {
        require(mobile.matchesLoginQr(qr)) { "QR относится к непривязанному сервису или отпечаток панели изменился. Создайте связь через «Ещё → Подключение»." }
        val data = panel.request("GET", "/api/mobile/security/login/${encodeSegment(qr.id)}", baseUrl = qr.origin).jsonObject
        val request = securityRequest(data)
        require(request.id == qr.id && request.nonce == qr.nonce && request.purpose == "login") { "QR больше не соответствует запросу входа. Обновите его в Web." }
        return request
    }

    suspend fun approveQr(qr: MobileLoginQr) {
        val request = loginPreview(qr)
        require(mayConfirmSecurity(refreshFor(qr.origin), request)) { "Вход при включённой 2FA подтверждает только основной телефон" }
        val url = PanelUrl.endpoint(qr.origin, "/api/mobile/security/decision")
        val signature = withContext(Dispatchers.IO) { mobile.signAuthDecision(url, qr.id, qr.nonce, "approve", qr.serverPin) }
        panel.request("POST", "/api/mobile/security/decision", baseUrl = qr.origin, body = buildJsonObject {
            put("id", qr.id); put("action", "approve"); put("signature", signature)
        })
    }

    suspend fun decide(id: String, action: String, origin: String? = null): SecurityRequest {
        val current = if (origin == null) refresh() else refreshFor(origin)
        val request = current.requests.firstOrNull { it.id == id } ?: error("Запрос уже завершён или истёк")
        require(mayConfirmSecurity(current, request)) { "Это действие доступно только основному телефону" }
        require(action in securityActions(request, current.now())) { "Это действие сейчас недоступно" }
        val url = PanelUrl.endpoint(current.origin, "/api/mobile/security/decision")
        val signature = withContext(Dispatchers.IO) { mobile.signAuthDecision(url, request.id, request.nonce, action) }
        panel.request("POST", "/api/mobile/security/decision", baseUrl = current.origin, body = buildJsonObject {
            put("id", request.id); put("action", action); put("signature", signature)
        })
        if (request.purpose == "unlink" && action == "approve") mobile.noteRevoked(url)
        else if (origin == null) refresh() else refreshFor(origin)
        return request
    }

    suspend fun acknowledge(id: String, origin: String? = null) {
        panel.request("POST", "/api/mobile/security/ack", baseUrl = origin, body = buildJsonObject { put("id", id) })
        if (origin == null) refresh() else refreshFor(origin)
    }

    suspend fun setupTotp(origin: String, enableRequestId: String? = null): JsonObject = panel.request("POST", "/api/mobile/security/totp/setup", baseUrl = origin,
        body = buildJsonObject { enableRequestId?.let { put("enable_request_id", it) } }).jsonObject
    suspend fun confirmTotp(origin: String, id: String, code: String): String? {
        val result = try {
            panel.request("POST", "/api/mobile/security/totp/confirm", baseUrl = origin, body = buildJsonObject { put("id", id); put("code", code) }).jsonObject
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val status = try { panel.request("GET", "/api/mobile/security/totp/setup/${encodeSegment(id)}", baseUrl = origin).jsonObject }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { throw error }
            if (!status.flag("registered")) throw error
            status
        }
        check(result.flag("registered")) { "TOTP на панели изменился. Проверьте регистрацию в Web." }
        check(result.flag("two_factor_enabled")) { "Панель не подтвердила включение 2FA. Обновите её и повторите подтверждение." }
        return result.text("totp_generation")
    }
}
