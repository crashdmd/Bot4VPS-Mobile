package com.bot4vps.mobile.data.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

enum class MasterKeyStep { LOADING, BLOCKED, LOCAL, SETUP_PIN, PASSWORD, CODE, READY, KEY }

fun masterKeyInitialStep(protected: Boolean, registered: Boolean, ownerChannelAvailable: Boolean): MasterKeyStep = when {
    protected && registered -> MasterKeyStep.LOCAL
    protected -> MasterKeyStep.SETUP_PIN
    !ownerChannelAvailable -> MasterKeyStep.BLOCKED
    else -> MasterKeyStep.PASSWORD
}

fun masterKeyUnavailableMessage(): String =
    "При выключенной защите приложения просмотр требует пароль администратора и второй фактор. " +
    "Сейчас нет доступного второго фактора. Включите PIN или биометрию в защите приложения либо настройте 2FA или Telegram в панели. " +
    "Также можно посмотреть ключ через CLI: bot4vps → Безопасность → Мастер-ключ."
data class MasterKeyViewState(val step: MasterKeyStep = MasterKeyStep.PASSWORD, val channel: String? = null,
    val key: String? = null, val busy: Boolean = false, val error: String? = null, val resendAt: Long = 0, val expiresAt: Long = 0)

/** Each step replaces the previous one. Passwords/codes are never stored in the state. */
class MasterKeyViewer(private val send: suspend (String, JsonObject) -> JsonObject, private val clock: () -> Long = System::currentTimeMillis,
    initialStep: MasterKeyStep = MasterKeyStep.PASSWORD) {
    private val mutable = MutableStateFlow(MasterKeyViewState(initialStep))
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var foreground = true
    private var confirmation: CompletableDeferred<Unit>? = null
    private var retryStep = MasterKeyStep.LOCAL
    fun prepare(step: MasterKeyStep) { confirmation?.cancel(); confirmation = null; generation++; mutable.value = MasterKeyViewState(step) }
    fun fail(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun blocked(message: String) { prepare(MasterKeyStep.BLOCKED); fail(message) }
    fun expire() { if (mutable.value.step == MasterKeyStep.KEY && clock() >= mutable.value.expiresAt) close() }
    fun close() { confirmation?.cancel(); confirmation = null; generation++; mutable.value = MasterKeyViewState() }
    fun foreground() { foreground = true }
    fun background(): Boolean {
        foreground = false
        val shown = mutable.value.step == MasterKeyStep.KEY
        if (shown) close()
        else if (mutable.value.step in setOf(MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN, MasterKeyStep.READY)) {
            confirmation?.cancel(); confirmation = null
            prepare(if (mutable.value.step == MasterKeyStep.READY) retryStep else mutable.value.step)
        }
        return shown
    }
    /** Local proof is temporary, never saved. Network/signing start only after approve(). */
    suspend fun authenticateLocal(authorize: suspend (suspend (ByteArray) -> Unit) -> Unit,
                                  perform: suspend (ByteArray) -> Unit) {
        val before = mutable.value
        if (before.step !in setOf(MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN) || before.busy || !foreground) return
        val ticket = generation
        mutable.value = before.copy(busy = true, error = null)
        try {
            authorize { seed ->
                if (generation != ticket || !foreground) return@authorize
                retryStep = before.step
                val answer = CompletableDeferred<Unit>()
                confirmation = answer
                mutable.value = MasterKeyViewState(MasterKeyStep.READY)
                try {
                    answer.await()
                    if (generation == ticket && foreground) {
                        mutable.value = MasterKeyViewState(retryStep, busy = false)
                        perform(seed)
                    }
                } finally { if (confirmation === answer) confirmation = null }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == ticket) mutable.value = before.copy(error = e.message ?: "Не удалось подтвердить личность") }
        finally { if (generation == ticket) mutable.value = mutable.value.copy(busy = false) }
    }
    fun approve() { if (mutable.value.step == MasterKeyStep.READY && foreground) confirmation?.complete(Unit) }
    private suspend fun request(path: String, body: JsonObject, transform: (JsonObject) -> MasterKeyViewState) {
        if (mutable.value.busy) return
        val ticket = generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            val response = send(path, body)
            check(response.flag("ok")) { "Панель не подтвердила действие" }
            if (generation == ticket) {
                val next = transform(response)
                mutable.value = if (!foreground && next.step == MasterKeyStep.KEY)
                    MasterKeyViewState(error = "Просмотр прерван при сворачивании. Повторите подтверждение.") else next
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == ticket) mutable.value = mutable.value.copy(error = e.message ?: "Не удалось выполнить действие") }
        finally { if (generation == ticket) mutable.value = mutable.value.copy(busy = false) }
    }
    suspend fun begin(password: String) {
        if (mutable.value.step != MasterKeyStep.PASSWORD || password.isEmpty()) return
        request("/api/masterkey/phone/view", buildJsonObject { put("password", password) }) { response ->
            val channel = response.text("channel")
            require(channel in setOf("totp", "telegram")) { "Способ подтверждения недоступен" }
            MasterKeyViewState(MasterKeyStep.CODE, channel = channel, resendAt = if (channel == "telegram") clock() + 120_000 else 0)
        }
    }
    suspend fun confirm(code: String) {
        if (mutable.value.step != MasterKeyStep.CODE || !code.matches(Regex("[0-9]{6}"))) return
        request("/api/masterkey/phone/view/confirm", buildJsonObject { put("code", code) }) { response ->
            val key = response.text("key")?.takeIf(String::isNotBlank) ?: error("Панель не вернула мастер-ключ")
            MasterKeyViewState(MasterKeyStep.KEY, key = key, expiresAt = clock() + 30_000)
        }
    }
    suspend fun resend() {
        val before = mutable.value
        if (before.step != MasterKeyStep.CODE || before.channel != "telegram" || clock() < before.resendAt) return
        request("/api/masterkey/phone/view/resend", buildJsonObject { }) { before.copy(busy = false, error = null, resendAt = clock() + 120_000) }
    }
    suspend fun enroll(publicKey: String, deviceOperationPublicKey: String, signDevice: suspend (JsonObject) -> String,
                       signOperation: suspend (JsonObject) -> String, signKeystore: suspend (JsonObject) -> String) {
        if (mutable.value.step != MasterKeyStep.SETUP_PIN || mutable.value.busy) return
        val ticket = generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            val challenge = send("/api/masterkey/phone/enroll/challenge", buildJsonObject { put("public_key", publicKey); put("device_operation_public_key", deviceOperationPublicKey) })
            check(challenge.flag("ok") && challenge.text("public_key") == publicKey &&
                challenge.text("device_operation_public_key") == deviceOperationPublicKey) { "Панель не подтвердила ключ операции" }
            val deviceSignature = signDevice(challenge)
            val operationSignature = signOperation(challenge)
            val keystoreSignature = signKeystore(challenge)
            if (generation != ticket) return
            val response = send("/api/masterkey/phone/enroll/confirm", buildJsonObject {
                put("challenge_id", challenge.text("challenge_id"))
                put("device_signature", deviceSignature); put("operation_signature", operationSignature)
                put("keystore_signature", keystoreSignature)
            })
            check(response.flag("ok") && response.flag("enrolled")) { "Панель не подтвердила настройку" }
            if (generation == ticket) mutable.value = MasterKeyViewState(MasterKeyStep.LOCAL)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == ticket) fail(e.message ?: "Не удалось настроить защищённый просмотр") }
        finally { if (generation == ticket) mutable.value = mutable.value.copy(busy = false) }
    }
    suspend fun local(signKeystore: (suspend (JsonObject) -> String)? = null, sign: suspend (JsonObject) -> String) {
        if (mutable.value.step != MasterKeyStep.LOCAL || mutable.value.busy) return
        val ticket = generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            val challenge = send("/api/masterkey/phone/challenge", buildJsonObject {})
            check(challenge.flag("ok")) { "Не удалось получить подтверждение" }
            val signature = sign(challenge)
            val keystoreSignature = signKeystore?.invoke(challenge)
            if (generation != ticket) return
            val response = send("/api/masterkey/phone/confirm", buildJsonObject {
                put("challenge_id", challenge.text("challenge_id")); put("signature", signature)
                keystoreSignature?.let { put("keystore_signature", it) }
            })
            val key = response.text("key")?.takeIf(String::isNotBlank) ?: error("Панель не вернула мастер-ключ")
            if (generation == ticket && foreground) mutable.value = MasterKeyViewState(MasterKeyStep.KEY, key = key, expiresAt = clock() + 30_000)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == ticket) fail(e.message ?: "Не удалось подтвердить просмотр") }
        finally { if (generation == ticket) mutable.value = mutable.value.copy(busy = false) }
    }
}
