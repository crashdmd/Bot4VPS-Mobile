package com.bot4vps.mobile.data.api

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PairedMobileDevice(val id: String, val alias: String, val serverPin: String? = null, val operationAlias: String? = null)
interface MobileDeviceKeys {
    fun origins(): List<String> = emptyList()
    fun device(origin: String): PairedMobileDevice?
    fun publicKey(origin: String): Pair<String, ByteArray>
    fun operationPublicKey(origin: String): Pair<String, ByteArray>
    fun remember(origin: String, device: PairedMobileDevice)
    fun forget(origin: String)
    fun migrate(oldOrigin: String, newOrigin: String, expected: PairedMobileDevice) { error("Перенос ключей не поддерживается") }
    fun sign(alias: String, message: ByteArray): ByteArray
}

class MobilePairingRequired(message: String) : IOException(message)

fun mobileChallengeMessage(device: String, challenge: String, nonce: String): ByteArray =
    "bot4vps-mobile-v1\n$device\n$challenge\n$nonce".toByteArray(Charsets.US_ASCII)

/** Only a paired origin receives a device token. Never follows redirects or uses HTTP. */
class MobileAccessClient(private val keys: MobileDeviceKeys, private val json: Json,
    private val transport: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val onRevoked: (String) -> Unit = {}) {
    private data class Session(val token: String, val expires: Long)
    private val sessions = mutableMapOf<String, Session>()
    private val revokedDevices = mutableMapOf<String, String>()
    private val pinnedClients = mutableMapOf<Pair<OkHttpClient, String>, OkHttpClient>()
    private val originLocks = mutableMapOf<String, Mutex>()
    private fun migrationTransport(url: HttpUrl, pin: String) = pinnedMobileClient(transport.newBuilder().cookieJar(CookieJar.NO_COOKIES).build(), url, pin)
    private fun lockFor(origin: String) = synchronized(this) { originLocks.getOrPut(origin) { Mutex() } }
    private fun origin(url: HttpUrl) = mobileOrigin(url)
    private fun secure(url: HttpUrl) { if (!url.isHttps) throw MobilePairingRequired("Для привязки телефона требуется HTTPS") }
    fun isPaired(url: HttpUrl): Boolean = url.isHttps && keys.device(origin(url))?.serverPin != null

    fun pairedDevices(): List<Pair<String, PairedMobileDevice>> = keys.origins().mapNotNull { origin -> keys.device(origin)?.let { origin to it } }

    @Synchronized
    fun validateChange(change: com.bot4vps.mobile.data.model.MobileConnectionChange): PairedMobileDevice {
        val device = keys.device(change.sourceOrigin) ?: keys.device(change.origin)?.takeIf { it.id == change.deviceId }
            ?: throw MobilePairingRequired("Прежняя связь с этим сервисом не найдена")
        require(device.serverPin == change.serverPin) { "Отпечаток сервиса изменился. Прежняя связь сохранена." }
        require(change.deviceId.isEmpty() || change.deviceId == device.id) { "Связь устройства изменилась" }
        val conflict = keys.device(change.origin)
        require(change.origin == change.sourceOrigin || conflict == null || conflict.id == device.id && conflict.alias == device.alias) { "Новый адрес уже используется другим подключением" }
        return device
    }

    /** Use the selected profile's device identity, never a QR address or pin alone. */
    @Synchronized
    fun sourceForChange(profileOrigin: String, expectedDeviceId: String, expectedPin: String? = null): Pair<String, PairedMobileDevice> {
        require(expectedDeviceId.isNotBlank()) { "У выбранного сервиса нет сохранённой привязки телефона" }
        val direct = keys.device(profileOrigin)
        if (direct != null) {
            require(direct.id == expectedDeviceId && direct.serverPin != null && (expectedPin == null || direct.serverPin == expectedPin)) { "QR относится к другой связи или отпечаток сервиса изменился" }
            return profileOrigin to direct
        }
        val candidates = pairedDevices().filter { (_, device) -> device.id == expectedDeviceId && device.serverPin != null && (expectedPin == null || device.serverPin == expectedPin) }
        require(candidates.size <= 1) { "Сохранены несколько адресов этой привязки. Сначала завершите прежнюю смену адреса." }
        return candidates.singleOrNull()
            ?: throw MobilePairingRequired("Ключ привязки выбранного сервиса отсутствует на телефоне. Новый адрес не подтверждён, прежние настройки панели сохранены.")
    }

    suspend fun discoverChange(oldOrigin: String, newUrl: String): com.bot4vps.mobile.data.model.MobileConnectionChange = withContext(Dispatchers.IO) {
        val destination = com.bot4vps.mobile.data.model.manualConnectionUrl(newUrl)
        val device = keys.device(oldOrigin) ?: throw MobilePairingRequired("Прежняя связь не найдена")
        val pin = device.serverPin ?: error("Нет отпечатка сервиса")
        val url = PanelUrl.endpoint(destination, "/api/mobile/connection-change/preview")
        val signature = Base64.getEncoder().encodeToString(keys.sign(device.alias,
            "bot4vps-mobile-discover-v1\n${device.id}\n$destination".toByteArray(Charsets.US_ASCII)))
        val result = post(url, buildJsonObject { put("device_id", device.id); put("url", destination); put("signature", signature) }, migrationTransport(url, pin))
        require(result["ok"]?.jsonPrimitive?.booleanOrNull == true && result["device_id"]?.jsonPrimitive?.content == device.id) { "Сервис не подтвердил прежнюю связь" }
        val change = if (result["state"]?.jsonPrimitive?.content == "pending") com.bot4vps.mobile.data.model.connectionChange(result.getValue("payload").jsonObject)
            else {
                require(result["state"]?.jsonPrimitive?.content == "approved") { "Смена адреса не подтверждена" }
                com.bot4vps.mobile.data.model.MobileConnectionChange(result.getValue("public_url").jsonPrimitive.content, oldOrigin, "", "", pin, device.id, approved = true)
            }
        require(change.origin == destination && change.serverPin == pin) { "Сервис вернул другой адрес подключения или отпечаток" }
        val bound = change.copy(deviceId = device.id, localOldUrl = oldOrigin)
        validateChange(bound)
        bound
    }

    suspend fun confirmChange(change: com.bot4vps.mobile.data.model.MobileConnectionChange) = withContext(Dispatchers.IO) {
        val device = validateChange(change)
        if (!change.approved) {
            val url = PanelUrl.endpoint(change.url, "/api/mobile/connection-change/confirm")
            val signature = Base64.getEncoder().encodeToString(keys.sign(device.alias, com.bot4vps.mobile.data.model.connectionChangeMessage(change, device.id)))
            val result = post(url, buildJsonObject { put("id", change.id); put("device_id", device.id); put("signature", signature) }, migrationTransport(url, change.serverPin))
            require(result["ok"]?.jsonPrimitive?.booleanOrNull == true && result["state"]?.jsonPrimitive?.content == "approved" &&
                result["device_id"]?.jsonPrimitive?.content == device.id && result["public_url"]?.jsonPrimitive?.content == change.url) { "Сервис не подтвердил сохранение нового адреса" }
        }
    }

    suspend fun migrateChange(change: com.bot4vps.mobile.data.model.MobileConnectionChange) = withContext(Dispatchers.IO) {
        val device = validateChange(change)
        keys.migrate(change.sourceOrigin, change.origin, device)
        synchronized(this@MobileAccessClient) { sessions.remove(change.sourceOrigin); sessions.remove(change.profileOrigin); sessions.remove(change.origin); pinnedClients.clear() }
    }

    fun noteRevoked(url: HttpUrl) {
        val address = origin(url)
        synchronized(this) { sessions.remove(address); keys.device(address)?.let { revokedDevices[address] = it.id } }
        onRevoked(address)
    }
    @Synchronized fun revokedDeviceId(address: String): String? = revokedDevices[address]
    fun reportRevoked(url: HttpUrl): Nothing { noteRevoked(url); throw MobileAccessRevoked(origin(url)) }

    @Synchronized
    fun signAuthDecision(url: HttpUrl, id: String, nonce: String, action: String, expectedPin: String? = null): String {
        secure(url)
        val device = keys.device(origin(url))?.takeIf { it.serverPin != null }
            ?: throw MobilePairingRequired("Сначала привяжите телефон к этой панели")
        require(expectedPin == null || expectedPin == device.serverPin) { "QR относится к другой панели или её отпечаток изменился" }
        val message = com.bot4vps.mobile.data.model.authDecisionMessage(id, nonce, device.id, action)
        return Base64.getEncoder().encodeToString(keys.sign(device.alias, message))
    }

    fun matchesLoginQr(qr: com.bot4vps.mobile.data.model.MobileLoginQr): Boolean = keys.device(qr.origin)?.serverPin == qr.serverPin

    @Synchronized
    fun clientFor(base: OkHttpClient, url: HttpUrl): OkHttpClient {
        secure(url)
        val pin = keys.device(origin(url))?.serverPin
            ?: throw MobilePairingRequired("Привяжите телефон по QR или полному коду из Web-панели")
        val cacheKey = base to (origin(url) + " " + pin.orEmpty())
        pinnedClients[cacheKey]?.let { return it }
        if (pinnedClients.size >= 32) pinnedClients.clear()
        val scoped = panelOriginClient(base, url)
        val currentPairing = Interceptor { chain ->
            if (keys.device(origin(url))?.serverPin != pin) {
                throw MobilePairingRequired("Привязка изменилась. Подключитесь к серверу заново.")
            }
            chain.proceed(chain.request())
        }
        return pinnedMobileClient(scoped, url, pin).newBuilder().apply {
            interceptors().add(0, currentPairing)
            addNetworkInterceptor(currentPairing)
        }.build()
            .also { pinnedClients[cacheKey] = it }
    }

    suspend fun pair(baseUrl: String, code: String, deviceName: String? = null): Unit = withContext(Dispatchers.IO) {
        val invitation = readMobileInvitation(code.trim())
        val url = PanelUrl.endpoint(invitation?.url ?: baseUrl, "/api/mobile/pair")
        secure(url)
        lockFor(origin(url)).withLock {
            val pin = invitation?.serverPin ?: keys.device(origin(url))?.serverPin
                ?: throw MobilePairingRequired("Нужен полный код привязки b4vps:… из Web-панели")
            val (alias, publicKey) = keys.publicKey(origin(url))
            val (_, operationPublicKey) = keys.operationPublicKey(origin(url))
            val result = post(url, buildJsonObject {
                put("code", invitation?.code ?: code.trim()); put("public_key", Base64.getEncoder().encodeToString(publicKey))
                put("masterkey_device_public_key", Base64.getEncoder().encodeToString(operationPublicKey))
                deviceName?.let { put("name", it) }
            }, pinnedMobileClient(transport, url, pin))
            val id = result["device_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Панель не подтвердила привязку телефона")
            currentCoroutineContext().ensureActive()
            keys.remember(origin(url), PairedMobileDevice(id, alias, pin))
            synchronized(this@MobileAccessClient) { sessions.remove(origin(url)); revokedDevices.remove(origin(url)) }
            Unit
        }
    }

    fun session(url: HttpUrl, force: Boolean = false, renewBeforeMillis: Long = 20000, caller: Call? = null): String {
        // Interceptors run on OkHttp workers. Cancellation of their outer call
        // also cancels a pending challenge/handshake or a wait for its mutex.
        return try {
            runBlocking {
                val attempt = currentCoroutineContext()
                val cancellation = caller?.let { call -> launch {
                    while (true) {
                        if (call.isCanceled()) { attempt[kotlinx.coroutines.Job]?.cancel(); break }
                        delay(50)
                    }
                } }
                try {
                    if (caller?.isCanceled() == true) throw IOException("Подключение отменено")
                    sessionAsync(url, force, renewBeforeMillis)
                } finally { cancellation?.cancel() }
            }
        } catch (e: CancellationException) { throw IOException("Подключение отменено", e) }
    }

    suspend fun sessionAsync(url: HttpUrl, force: Boolean = false, renewBeforeMillis: Long = 20000): String = withContext(Dispatchers.IO) {
        secure(url)
        val origin = origin(url)
        lockFor(origin).withLock {
        val device = keys.device(origin)?.takeIf { it.serverPin != null }
            ?: throw MobilePairingRequired("Привяжите телефон по QR или полному коду из Web-панели")
        synchronized(this@MobileAccessClient) {
            sessions[origin]?.takeIf { !force && it.expires > System.currentTimeMillis() + renewBeforeMillis }
        }?.let { return@withLock it.token }
        synchronized(this@MobileAccessClient) { sessions.remove(origin) }
        try {
            val challenge = post(PanelUrl.endpoint(origin, "/api/mobile/challenge"), buildJsonObject { put("device_id", device.id) })
            val id = challenge["challenge_id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Нет подтверждения панели")
            val nonce = challenge["nonce"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Нет подтверждения панели")
            if (id.length > 128 || nonce.length > 128) throw IOException("Неверное подтверждение панели")
            val proof = keys.sign(device.alias, mobileChallengeMessage(device.id, id, nonce))
            val result = post(PanelUrl.endpoint(origin, "/api/mobile/handshake"), buildJsonObject {
                put("device_id", device.id); put("challenge_id", id); put("signature", Base64.getEncoder().encodeToString(proof))
            })
            val token = result["session"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 40..64 }
                ?: throw IOException("Панель не подтвердила доступ телефона")
            val seconds = result["expires_in"]?.jsonPrimitive?.longOrNull?.coerceIn(30, 900) ?: 900L
            currentCoroutineContext().ensureActive()
            synchronized(this@MobileAccessClient) { sessions[origin] = Session(token, System.currentTimeMillis() + seconds * 1000) }
            token
        } catch (e: ApiError) {
            if (e.code == "revoked") reportRevoked(url)
            if (e.status in setOf(401, 403, 428)) throw MobilePairingRequired("Доступ телефона отозван или ключ недоступен. Привяжите телефон заново.")
            throw e
        } catch (e: java.security.GeneralSecurityException) {
            throw MobilePairingRequired("Ключ телефона недоступен. Привяжите телефон заново.")
        }
        }
    }

    fun forget(url: HttpUrl, removeOperationKeys: (String) -> Unit = {}) = forgetIfDevice(url, null, removeOperationKeys)

    fun forgetIfDevice(url: HttpUrl, expectedDeviceId: String?, removeOperationKeys: (String) -> Unit = {}) {
        val origin = origin(url)
        runBlocking { lockFor(origin).withLock {
            synchronized(this@MobileAccessClient) {
                val storedDevice = keys.device(origin)
                if (expectedDeviceId != null && storedDevice != null && storedDevice.id != expectedDeviceId) return@withLock
                removeOperationKeys(origin)
                sessions.remove(origin)
                pinnedClients.clear()
                keys.forget(origin)
            }
        } }
    }

    @Synchronized
    fun signMasterKeyEnrollment(url: HttpUrl, challenge: JsonObject, publicKey: String): String {
        secure(url)
        val device = keys.device(origin(url))?.takeIf { it.serverPin != null }
            ?: throw MobilePairingRequired("Привяжите телефон заново")
        require(challenge["device_id"]?.jsonPrimitive?.content == device.id &&
            challenge["public_key"]?.jsonPrimitive?.content == publicKey) { "Панель вернула подтверждение другого телефона или ключа" }
        val id = challenge["challenge_id"]?.jsonPrimitive?.content ?: error("Нет подтверждения")
        val nonce = challenge["nonce"]?.jsonPrimitive?.content ?: error("Нет подтверждения")
        val nativePublicKey = operationPublicKey(url)
        require(challenge["device_operation_public_key"]?.jsonPrimitive?.content == nativePublicKey) { "Панель вернула подтверждение другого ключа Keystore" }
        val message = com.bot4vps.mobile.data.model.masterKeyEnrollmentMessage(device.id, id, nonce, publicKey, nativePublicKey)
        return Base64.getEncoder().encodeToString(keys.sign(device.alias, message))
    }

    @Synchronized
    fun operationPublicKey(url: HttpUrl): String {
        secure(url)
        require(isPaired(url)) { "Привяжите телефон заново" }
        return Base64.getEncoder().encodeToString(keys.operationPublicKey(origin(url)).second)
    }

    @Synchronized
    fun signMasterKeyOperation(url: HttpUrl, challenge: JsonObject, enrollmentPublicKey: String? = null): String {
        secure(url)
        val device = keys.device(origin(url)) ?: throw MobilePairingRequired("Привяжите телефон заново")
        require(challenge["device_id"]?.jsonPrimitive?.content == device.id) { "Панель вернула подтверждение другого телефона" }
        val id = challenge["challenge_id"]?.jsonPrimitive?.content ?: error("Нет подтверждения")
        val nonce = challenge["nonce"]?.jsonPrimitive?.content ?: error("Нет подтверждения")
        val (alias, bytes) = keys.operationPublicKey(origin(url))
        val message = if (enrollmentPublicKey != null) {
            val nativePublicKey = Base64.getEncoder().encodeToString(bytes)
            require(challenge["device_operation_public_key"]?.jsonPrimitive?.content == nativePublicKey) { "Панель вернула другой ключ Keystore" }
            com.bot4vps.mobile.data.model.masterKeyEnrollmentMessage(device.id, id, nonce, enrollmentPublicKey, nativePublicKey)
        } else {
            require(listOf(id, nonce).all { it.isNotBlank() && '\n' !in it && it.length <= 128 })
            "bot4vps-masterkey-view-v1\n${device.id}\n$id\n$nonce".toByteArray(Charsets.US_ASCII)
        }
        return Base64.getEncoder().encodeToString(keys.sign(alias, message))
    }

    private suspend fun post(url: HttpUrl, data: JsonObject, client: OkHttpClient = clientFor(transport, url)): JsonObject = coroutineScope {
        val request = Request.Builder().url(url).header("X-Bot4VPS-Client", "phone").post(data.toString().toRequestBody("application/json".toMediaType())).build()
        val call = panelOriginClient(client, url).newBuilder().callTimeout(PANEL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).build().newCall(request)
        val cancellation = launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            val result = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (!response.isSuccessful) throw ApiError(response.code, result?.get("detail")?.jsonPrimitive?.contentOrNull ?: "Панель не подтвердила подключение", response.header("X-Bot4VPS-Mobile-Error"))
            result ?: throw IOException("Неверное подтверждение панели")
        } } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally { cancellation.cancel() }
    }
}

/** Browser assets share the existing device lease; renewal stays explicit. */
object ReuseMobileSocketSession

class MobileAccessInterceptor(private val mobile: MobileAccessClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request().newBuilder().removeHeader("X-Bot4VPS-Mobile-Session").removeHeader("Cookie").header("X-Bot4VPS-Client", "phone").build()
        val reuse = original.tag(ReuseMobileSocketSession::class.java) != null
        val token = mobile.session(original.url, force = !reuse && original.header("Upgrade")?.equals("websocket", ignoreCase = true) == true, renewBeforeMillis = if (reuse) 90000 else 20000, caller = chain.call())
        val request = original.newBuilder().header("X-Bot4VPS-Mobile-Session", token).build()
        val response = chain.proceed(request)
        if (response.header("X-Bot4VPS-Mobile-Error") == "revoked") { response.close(); mobile.reportRevoked(original.url) }
        if (response.code == 401 && response.header("X-Bot4VPS-Mobile-Error") == "session") {
            response.close()
            val fresh = mobile.session(original.url, force = true, caller = chain.call())
            return chain.proceed(original.newBuilder().header("X-Bot4VPS-Mobile-Session", fresh).build())
        }
        return response
    }
}
