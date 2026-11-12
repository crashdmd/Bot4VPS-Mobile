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

data class PairedMobileDevice(val id: String, val alias: String, val serverPin: String? = null)
interface MobileDeviceKeys {
    fun device(origin: String): PairedMobileDevice?
    fun publicKey(origin: String): Pair<String, ByteArray>
    fun operationPublicKey(origin: String): Pair<String, ByteArray>
    fun remember(origin: String, device: PairedMobileDevice)
    fun forget(origin: String)
    fun sign(alias: String, message: ByteArray): ByteArray
}

class MobilePairingRequired(message: String) : IOException(message)

fun mobileChallengeMessage(device: String, challenge: String, nonce: String): ByteArray =
    "bot4vps-mobile-v1\n$device\n$challenge\n$nonce".toByteArray(Charsets.US_ASCII)

/** Only a paired origin receives a device token. Never follows redirects or uses HTTP. */
class MobileAccessClient(private val keys: MobileDeviceKeys, private val json: Json,
    private val transport: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    private data class Session(val token: String, val expires: Long)
    private val sessions = mutableMapOf<String, Session>()
    private val pinnedClients = mutableMapOf<Pair<OkHttpClient, String>, OkHttpClient>()
    private val originLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(origin: String) = synchronized(this) { originLocks.getOrPut(origin) { Mutex() } }
    private fun origin(url: HttpUrl) = mobileOrigin(url)
    private fun secure(url: HttpUrl) { if (!url.isHttps) throw MobilePairingRequired("Для привязки телефона требуется HTTPS") }
    fun isPaired(url: HttpUrl): Boolean = url.isHttps && keys.device(origin(url))?.serverPin != null

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

    suspend fun pair(baseUrl: String, code: String): Unit = withContext(Dispatchers.IO) {
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
            }, pinnedMobileClient(transport, url, pin))
            val id = result["device_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Панель не подтвердила привязку телефона")
            currentCoroutineContext().ensureActive()
            keys.remember(origin(url), PairedMobileDevice(id, alias, pin))
            synchronized(this@MobileAccessClient) { sessions.remove(origin(url)) }
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
            if (e.status in setOf(401, 403, 428)) throw MobilePairingRequired("Доступ телефона отозван или ключ недоступен. Привяжите телефон заново.")
            throw e
        } catch (e: java.security.GeneralSecurityException) {
            throw MobilePairingRequired("Ключ телефона недоступен. Привяжите телефон заново.")
        }
        }
    }

    fun forget(url: HttpUrl, removeOperationKeys: (String) -> Unit = {}) {
        val origin = origin(url)
        runBlocking { lockFor(origin).withLock {
            synchronized(this@MobileAccessClient) {
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
            if (!response.isSuccessful) throw ApiError(response.code, result?.get("detail")?.jsonPrimitive?.contentOrNull ?: "Панель не подтвердила подключение")
            result ?: throw IOException("Неверное подтверждение панели")
        } } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally { cancellation.cancel() }
    }
}

class MobileAccessInterceptor(private val mobile: MobileAccessClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request().newBuilder().removeHeader("X-Bot4VPS-Mobile-Session").removeHeader("Cookie").header("X-Bot4VPS-Client", "phone").build()
        val token = mobile.session(original.url, force = original.header("Upgrade")?.equals("websocket", ignoreCase = true) == true, caller = chain.call())
        val request = original.newBuilder().header("X-Bot4VPS-Mobile-Session", token).build()
        val response = chain.proceed(request)
        if (response.code == 401 && response.header("X-Bot4VPS-Mobile-Error") == "session") {
            response.close()
            val fresh = mobile.session(original.url, force = true, caller = chain.call())
            return chain.proceed(original.newBuilder().header("X-Bot4VPS-Mobile-Session", fresh).build())
        }
        return response
    }
}
