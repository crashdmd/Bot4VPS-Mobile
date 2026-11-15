package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.Bot4VpsApi
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.local.AppLockManager
import com.bot4vps.mobile.data.local.SensitiveKeyStorage
import com.bot4vps.mobile.data.model.MeResponse
import com.bot4vps.mobile.di.ApiFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bot4vps.mobile.data.model.ConnectionNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bot4vps.mobile.data.api.connectToPanel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.bot4vps.mobile.data.api.SessionCookieJar
import com.bot4vps.mobile.data.api.PanelUrl
import com.bot4vps.mobile.data.api.MobileAccessClient
import com.bot4vps.mobile.data.api.readMobileInvitation

@Singleton
class AuthRepository @Inject constructor(
    private val prefs: PreferencesManager,
    private val apiFactory: ApiFactory,
    private val cookies: SessionCookieJar,
    private val mobile: MobileAccessClient,
    private val panel: com.bot4vps.mobile.data.api.NativePanelClient,
    private val operationKeys: SensitiveKeyStorage,
    private val appLock: AppLockManager,
    private val secrets: com.bot4vps.mobile.data.local.SecretStorage,
    private val authenticator: com.bot4vps.mobile.data.local.AuthenticatorStorage,
    private val workflow: com.bot4vps.mobile.data.model.NativeWorkflow,
    private val deviceName: com.bot4vps.mobile.data.local.DeviceNameProvider
) {
    private val connectionLock = Mutex()
    private val notice = MutableStateFlow<ConnectionNotice?>(null)
    val connectionNotice = notice.asStateFlow()
    fun dismissConnectionNotice() { notice.value = null }
    val baseUrl: Flow<String?> = prefs.baseUrl
    val username: Flow<String?> = prefs.username
    private val migrationMessage = MutableStateFlow<String?>(null)
    val migrationNotice = migrationMessage.asStateFlow()
    fun dismissMigrationNotice() { migrationMessage.value = null }

    private fun pendingMigration() = secrets.readStrict("pending_api_migration")?.let {
        kotlinx.serialization.json.Json.decodeFromString<com.bot4vps.mobile.data.model.MobileConnectionChange>(it)
    }
    private fun rememberMigration(change: com.bot4vps.mobile.data.model.MobileConnectionChange?) {
        secrets.write(change?.let { kotlinx.serialization.json.Json.encodeToString(com.bot4vps.mobile.data.model.MobileConnectionChange.serializer(), it) }, "pending_api_migration")
    }

    private suspend fun currentMigration() = reconcileConnectionMigration(
        { withContext(Dispatchers.IO) { pendingMigration() } },
        { withContext(Dispatchers.IO) { mobile.pairedDevices() } }, { prefs.profiles.first() },
        { change -> withContext(Dispatchers.IO) {
            val identity = "${change.deviceId}\n${change.id}\n${change.sourceOrigin}\n${change.origin}"
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            secrets.write(kotlinx.serialization.json.Json.encodeToString(com.bot4vps.mobile.data.model.MobileConnectionChange.serializer(), change), "obsolete_api_migration_$hash")
        } }, { withContext(Dispatchers.IO) { rememberMigration(it) } })

    suspend fun previewAddressChange(value: String): com.bot4vps.mobile.data.model.MobileConnectionChange = connectionLock.withLock {
        withContext(Dispatchers.IO) {
            previewConnectionMigration(value, mobile,
                { resumeApprovedConnectionMigration(::currentMigration, ::commitMigration); Unit },
                { prefs.baseUrl.first() to prefs.profiles.first() }, { old, new -> operationKeys.validateMigration(old, new) })
        }
    }

    suspend fun changeAddress(change: com.bot4vps.mobile.data.model.MobileConnectionChange) = connectionLock.withLock {
        val recovered = resumeApprovedConnectionMigration(::currentMigration, ::commitMigration)
        if (recovered != null && recovered.id == change.id && recovered.origin == change.origin && recovered.deviceId == change.deviceId) return@withLock
        val pending = currentMigration()
        val sameRequest = pending != null && pending.id == change.id && pending.sourceOrigin == change.sourceOrigin && pending.origin == change.origin && pending.deviceId == change.deviceId
        val current = if (pending != null && !sameRequest) {
            // A fresh staged request proves the previous attempt was superseded.
            // Keep its journal until the new endpoint has attested this fact.
            val verified = mobile.discoverChange(change.sourceOrigin, change.url).copy(profileOldUrl = change.profileOldUrl)
            check(verified.approved || verified.id == change.id && verified.nonce == change.nonce) { "QR уже изменился. Отсканируйте актуальные данные." }
            verified
        } else pending ?: change
        val device = withContext(Dispatchers.IO) { mobile.validateChange(current); operationKeys.validateMigration(current.sourceOrigin, current.origin); mobile.validateChange(current) }
        validateConnectionMigrationProfiles(prefs.profiles.first(), current, device.id)
        withContext(Dispatchers.IO) { rememberMigration(current) }
        commitMigration(current)
    }

    private suspend fun commitMigration(change: com.bot4vps.mobile.data.model.MobileConnectionChange) = withContext(Dispatchers.IO) {
        runConnectionMigration(change, ::rememberMigration, {
            try { mobile.confirmChange(change) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                // Recovery uses the new endpoint and the same retained device key.
                val status = try { mobile.discoverChange(change.sourceOrigin, change.url) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (statusError: Exception) {
                    if (statusError is com.bot4vps.mobile.data.api.ApiError && statusError.status in setOf(403, 404, 410)) rememberMigration(null)
                    throw error
                }
                if (!status.approved || status.deviceId != change.deviceId) {
                    if (status.id != change.id) rememberMigration(null)
                    throw error
                }
            }
        },
            { operationKeys.migrate(change.sourceOrigin, change.origin, alreadyMoved = !mobile.isPaired(PanelUrl.endpoint(change.sourceOrigin, "/api/me"))) },
            { authenticator.migrate(change.sourceOrigin, change.origin); if (change.profileOrigin != change.sourceOrigin) authenticator.migrate(change.profileOrigin, change.origin) },
            { mobile.migrateChange(change.copy(approved = true)) },
            { prefs.migrateProfile(change.profileOrigin, change.origin, change.deviceId); if (change.sourceOrigin != change.profileOrigin) prefs.migrateProfile(change.sourceOrigin, change.origin, change.deviceId); operationKeys.finishMigration(change.sourceOrigin, change.origin); cookies.clear(); workflow.clear() },
            preflight = {
                val device = mobile.validateChange(change.copy(approved = true))
                validateConnectionMigrationProfiles(prefs.profiles.first(), change, device.id)
                operationKeys.validateMigration(change.sourceOrigin, change.origin, alreadyMoved = !mobile.isPaired(PanelUrl.endpoint(change.sourceOrigin, "/api/me")))
            })
        migrationMessage.value = "Адрес сервиса изменён: ${change.oldUrl} → ${change.url}. Подключение и коды 2FA сохранены."
    }

    private suspend fun api(): Bot4VpsApi {
        val url = prefs.baseUrl.first()
            ?: throw IllegalStateException("Base URL not set")
        return withContext(Dispatchers.IO) { apiFactory.create(url) }
    }

    suspend fun setBaseUrl(url: String) {
        val normalized = PanelUrl.normalize(url)
        if (prefs.baseUrl.first() != normalized) {
            cookies.clear()
            prefs.clearSession()
            prefs.rememberConnection(null)
            workflow.clear()
        }
        prefs.setBaseUrl(normalized)
    }

    val profiles = prefs.profiles

    suspend fun refreshProfiles() = connectionLock.withLock { refreshProfilesUnlocked() }

    private suspend fun refreshProfilesUnlocked() {
        val pending = currentMigration()
        if (pending != null) {
            try { commitMigration(pending) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* Retain the journal and previous connection. */ }
            // Never infer or republish profiles from a partially moved key
            // registry; the approved journal owns recovery until it completes.
            if (withContext(Dispatchers.IO) { pendingMigration() }?.approved == true) return
        }
        prefs.profiles.first().filter { it.revoked || it.localDisconnected }.forEach { profile ->
            val seed = appLock.sensitiveSeedId
            withContext(Dispatchers.IO) { mobile.forgetIfDevice(PanelUrl.endpoint(profile.url, "/api/me"), profile.deviceId) { address -> operationKeys.remove(address, seed) } }
            dropConnection(profile.url, profile.deviceId, profile.revoked)
        }
        val old = prefs.profiles.first().associateBy { it.url }
        withContext(Dispatchers.IO) { mobile.pairedDevices() }.forEach { (origin, device) ->
            if (origin !in old) prefs.rememberProfile(com.bot4vps.mobile.data.model.PanelProfile(origin, origin, device.id))
        }
    }

    suspend fun selectProfile(origin: String) = connectionLock.withLock {
        val profile = prefs.profiles.first().firstOrNull { it.url == origin } ?: error("Подключение не найдено")
        check(!profile.revoked) { "Доступ этого телефона отозван. Создайте новую связь с сервисом." }
        check(!profile.localDisconnected) { "Связь удалена с телефона. Создайте новую связь с сервисом." }
        setBaseUrl(profile.url)
        notice.value?.takeIf { it.choicesRequired }?.let { previous ->
            notice.value = previous.copy(nextOrigin = profile.url, choicesRequired = false, id = System.nanoTime())
        }
    }

    private suspend fun dropConnection(origin: String, expectedId: String, revoked: Boolean, roleMessage: String? = null) {
        val removed = prefs.removeProfile(origin, expectedId)
        if (removed.removed != null) {
            if (removed.switched) { cookies.clear(); workflow.clear() }
            notice.value = ConnectionNotice(origin, removed.nextOrigin, removed.switched, revoked, removed.choicesRequired, roleMessage = roleMessage)
        }
    }

    suspend fun recordRevocation(origin: String) = connectionLock.withLock {
        val expectedId = mobile.revokedDeviceId(origin) ?: return@withLock
        val seed = appLock.sensitiveSeedId
        withContext(Dispatchers.IO) {
            mobile.forgetIfDevice(PanelUrl.endpoint(origin, "/api/me"), expectedId) { address -> operationKeys.remove(address, seed) }
        }
        dropConnection(origin, expectedId, revoked = true)
    }

    suspend fun checkSession(): MeResponse = connectToPanel {
        refreshProfiles()
        val base = prefs.baseUrl.first() ?: error("Укажите адрес панели")
        val origin = com.bot4vps.mobile.data.api.mobileOrigin(PanelUrl.endpoint(base, "/api/me"))
        if (prefs.profiles.first().any { it.url == origin && it.revoked }) throw com.bot4vps.mobile.data.api.MobileAccessRevoked(origin)
        mobile.sessionAsync(PanelUrl.endpoint(base, "/api/me"))
        val result = withContext(Dispatchers.IO) { apiFactory.create(base) }.me()
        check(prefs.baseUrl.first() == base) { "Текущий сервис изменился. Подключитесь заново." }
        check(result.authenticated) { "Сервер не подтвердил мобильный вход. Обновите Bot4VPS до версии с входом по ключу телефона." }
        if (prefs.username.first().isNullOrBlank()) prefs.setUsername(result.user ?: "Мобильный доступ")
        refreshProfiles()
        result
    }

    suspend fun pairMobile(code: String): Unit = connectionLock.withLock { connectToPanel {
        val target = readMobileInvitation(code.trim())?.url ?: prefs.baseUrl.first() ?: error("Укажите адрес панели")
        mobile.pair(target, code, deviceName.name())
        setBaseUrl(target)
        val paired = withContext(Dispatchers.IO) { mobile.pairedDevices() }.firstOrNull { it.first == com.bot4vps.mobile.data.api.mobileOrigin(PanelUrl.endpoint(target, "/api/me")) }
        paired?.let { (origin, device) ->
            val existing = prefs.profiles.first().firstOrNull { it.url == origin }
            prefs.rememberProfile(com.bot4vps.mobile.data.model.PanelProfile(origin, existing?.name ?: origin, device.id))
            prefs.offerTotp(origin)
            currentMigration()
        }
    }
    }
    fun isPairedApiAddress(baseUrl: String): Boolean = mobile.isPaired(PanelUrl.endpoint(baseUrl, "/api/me"))

    suspend fun logout() = connectionLock.withLock {
        refreshProfilesUnlocked()
        prefs.baseUrl.first()?.let { base ->
            val url = PanelUrl.endpoint(base, "/api/me")
            val origin = com.bot4vps.mobile.data.api.mobileOrigin(url)
            val expectedDevice = prefs.profiles.first().firstOrNull { it.url == origin }?.deviceId
            var roleMessage: String? = null
            try {
                val response = panel.request("POST", "/api/mobile/security/disconnect", baseUrl = origin)
                val objectResponse = response as? kotlinx.serialization.json.JsonObject
                check(expectedDevice != null && objectResponse?.get("ok") == kotlinx.serialization.json.JsonPrimitive(true) && objectResponse["device_id"] == kotlinx.serialization.json.JsonPrimitive(expectedDevice)) { "Панель не подтвердила отвязку этого телефона. Связь с телефона сохранена." }
                roleMessage = objectResponse["primary_device_name"]?.jsonPrimitive?.contentOrNull?.let { "Телефон «$it» автоматически назначен основным." }
                    ?: if (objectResponse["primary_selection_required"] == kotlinx.serialization.json.JsonPrimitive(true)) "Выберите новый основной телефон в веб-панели, подтвердив выбор только паролем администратора." else null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Only an explicit, device-bound revocation marker counts as success after a lost response.
                if (expectedDevice == null || mobile.revokedDeviceId(origin) != expectedDevice) throw e
            }
            val seedId = appLock.sensitiveSeedId
            withContext(Dispatchers.IO) {
                runCatching { PanelUrl.endpoint(base, "/api/me") }.getOrNull()?.let { url ->
                    mobile.forgetIfDevice(url, expectedDevice, { origin -> operationKeys.remove(origin, seedId) })
                }
            }
            if (expectedDevice != null) dropConnection(origin, expectedDevice, revoked = false, roleMessage = roleMessage)
        }
    }
}
