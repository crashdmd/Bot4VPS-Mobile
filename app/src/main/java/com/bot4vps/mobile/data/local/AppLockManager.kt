package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

data class AppLockState(
    val phase: LockPhase = LockPhase.LOADING,
    val foreground: Boolean = false,
    val generation: Long = 0,
    val biometricEnabled: Boolean = false,
    val busy: Boolean = false,
    val retryAt: Long = 0,
    val error: String? = null,
    val revision: Int = 0,
    val legacyPassword: Boolean = false,
    val needsPinSetup: Boolean = false,
    val usePin: Boolean = true,
    val pinChangeAuthorized: Boolean = false,
) {
    val enabled get() = phase != LockPhase.DISABLED && phase != LockPhase.LOADING
    val protectsContent get() = phase != LockPhase.DISABLED
    val allowsContent get() = phase == LockPhase.DISABLED || phase == LockPhase.UNLOCKED
}

data class LockBiometricRequest(val id: Long, val cipher: Cipher, val enrollmentAlias: String? = null,
    internal val seed: ByteArray? = null, val sensitiveOperation: Boolean = false)

@Singleton
class AppLockManager internal constructor(
    private val secrets: AppLockStorage,
    private val biometrics: AppLockBiometricOperations,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val seedProtection: AppLockSeedProtection,
    lockClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Inject constructor(secrets: SecretStorage, biometrics: AppLockBiometrics, seedProtection: AndroidAppLockSeedProtection) : this(
        object : AppLockStorage {
            override fun read() = secrets.readStrict(SLOT)
            override fun write(value: String?) = secrets.write(value, SLOT)
        }, biometrics, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), seedProtection = seedProtection,
        lockClock = android.os.SystemClock::elapsedRealtime,
    )
    private val session = LockSession(lockClock)
    private var backgroundLockJob: Job? = null
    private var record: AppLockRecord? = null
    private var busy = false
    private var error: String? = null
    private var revision = 0
    private var legacyTicket: Long? = null
    private var changeTicket: Long? = null
    private var changeSeed: ByteArray? = null
    private var sensitiveResult: CompletableDeferred<ByteArray>? = null
    private var biometricSeed: ByteArray? = null
    private var activeSeed: ByteArray? = null
    val sensitiveSeedId get() = record?.sensitiveSeed?.id
    private var preferPin = false
    private val mutableState = MutableStateFlow(AppLockState())
    val state = mutableState.asStateFlow()
    private val mutableSecureViews = MutableStateFlow(0)
    val secureViews = mutableSecureViews.asStateFlow()
    fun protectScreen() { mutableSecureViews.value++ }
    fun releaseScreen() { mutableSecureViews.value = (mutableSecureViews.value - 1).coerceAtLeast(0) }
    private val mutablePrompt = MutableStateFlow<LockBiometricRequest?>(null)
    val prompt = mutablePrompt.asStateFlow()
    init { reload() }

    private fun publish() { mutableState.value = AppLockState(session.phase, session.foreground, session.generation, record?.biometric != null, busy, record?.retryAt ?: 0, error, revision,
        record?.needsPin == true, legacyTicket?.let(session::valid) == true, preferPin || record?.biometric == null,
        changeTicket?.let(session::valid) == true) }
    fun foreground() { backgroundLockJob?.cancel(); backgroundLockJob = null; session.foreground(); publish() }
    fun background() {
        if (!session.foreground) return
        session.background(); legacyTicket = null; cancelPinChange(); preferPin = false; cancelPrompt(); error = null; publish()
        backgroundLockJob?.cancel()
        backgroundLockJob = scope.launch {
            delay(LockSession.BACKGROUND_LOCK_DELAY)
            session.expireBackground(); publish()
        }
    }
    fun lock() { session.lock(); legacyTicket = null; cancelPinChange(); preferPin = false; cancelPrompt(); error = null; publish() }
    fun showPin() { preferPin = true; cancelPrompt(); publish() }
    fun cancelPinSetup() { legacyTicket = null; changeTicket = null; preferPin = true; error = null; publish() }
    fun cancelPinChange() { changeTicket = null; changeSeed?.fill(0); changeSeed = null; publish() }
    fun clearError() { error = null; publish() }

    fun reload() {
        if (busy) return
        busy = true; error = null; publish()
        scope.launch {
            try {
                record = withContext(ioDispatcher) { secrets.read()?.let(::decodeAppLockRecord) }
                legacyTicket = null; changeTicket = null
                session.loaded(record != null)
            } catch (_: Exception) {
                session.unavailable(); error = "Не удалось прочитать настройки защиты. Доступ к приложению заблокирован."
            } finally { busy = false; publish() }
        }
    }
    private suspend fun persist(value: AppLockRecord?) {
        try { withContext(ioDispatcher) { secrets.write(value?.let { Json.encodeToString(it) }) }; record = value }
        catch (e: Exception) { session.unavailable(); throw IllegalStateException("Не удалось сохранить настройки защиты. Повторите проверку.", e) }
    }
    private fun pinAction(current: String?, allowSetupProof: Boolean = false, action: suspend (Long) -> Unit) {
        if (busy || mutablePrompt.value != null) return
        val ticket = session.ticket() ?: return
        val password = current?.toCharArray()
        busy = true; error = null; publish()
        scope.launch {
            try {
                val saved = record
                val hasSetupProof = allowSetupProof && ((saved?.needsPin == true && legacyTicket == ticket) || changeTicket == ticket)
                if (saved != null && !hasSetupProof) {
                    require(saved.remainingMillis(System.currentTimeMillis()) == 0L) { "Слишком много попыток. Подождите перед повторным вводом." }
                    require(password != null) { "Введите текущий PIN-код" }
                    val verified = withContext(cryptoDispatcher) { saved.credential.verify(password) }
                    if (!session.valid(ticket)) return@launch
                    if (!verified) { persist(saved.failed(System.currentTimeMillis())); error(if (saved.needsPin) "Неверный пароль предыдущей версии" else "Неверный PIN-код") }
                    if (saved.failedAttempts != 0 || saved.retryAt != 0L) persist(saved.successful())
                }
                if (session.valid(ticket)) action(ticket)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось выполнить действие" }
            finally { password?.fill('\u0000'); busy = false; publish() }
        }
    }
    fun unlock(pin: String) { pinAction(pin) { ticket ->
        if (record?.needsPin == true) legacyTicket = ticket else session.unlock(ticket)
    } }
    fun authorizePinChange(pin: String) { pinAction(pin) { ticket ->
        check(record?.needsPin == false) { "Сначала завершите настройку PIN-кода" }
        changeSeed?.fill(0)
        val chars = pin.toCharArray()
        changeSeed = try { withContext(cryptoDispatcher) { record?.sensitiveSeed?.let { seedProtection.unprotect(it).open(chars) } } } finally { chars.fill('\u0000') }
        changeTicket = ticket
    } }
    fun setPin(current: String?, password: String, confirmation: String) {
        if (password != confirmation) { error = "PIN-коды не совпадают"; publish(); return }
        if (password.length != LockPin.LENGTH || password.any { it !in '0'..'9' }) { error = "PIN-код должен содержать 4 цифры"; publish(); return }
        pinAction(current, allowSetupProof = true) { ticket ->
            val chars = password.toCharArray()
            val seed = changeSeed ?: current?.toCharArray()?.let { old ->
                try { withContext(cryptoDispatcher) { record?.sensitiveSeed?.let { seedProtection.unprotect(it).open(old) } } } finally { old.fill('\u0000') }
            } ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val id = record?.sensitiveSeed?.id ?: java.util.UUID.randomUUID().toString()
            val (digest, wrapped) = try { withContext(cryptoDispatcher) { LockPin.create(chars) to seedProtection.protect(SensitivePinSeed.wrap(chars, seed, id)) } }
                finally { chars.fill('\u0000'); seed.fill(0); changeSeed = null }
            if (session.valid(ticket)) {
                persist(AppLockRecord(pin = digest, biometric = record?.biometric, sensitiveSeed = wrapped))
                legacyTicket = null; changeTicket = null; session.configured(true, ticket); revision++
            }
        }
    }
    fun disable(pin: String) { pinAction(pin) { ticket ->
        if (session.valid(ticket)) {
            val alias = record?.biometric?.alias
            val seedId = record?.sensitiveSeed?.id
            persist(null); session.configured(false); revision++
            withContext(ioDispatcher) { biometrics.remove(alias) }
            if (seedId != null) withContext(ioDispatcher) { seedProtection.remove(seedId) }
        }
    } }
    fun setBiometric(enabled: Boolean, pin: String) { pinAction(pin) { ticket ->
        val saved = record ?: error("Сначала включите блокировку приложения")
        check(!saved.needsPin) { "Сначала задайте PIN-код" }
        if (!enabled) {
            persist(saved.copy(biometric = null)); session.configured(true, ticket); revision++
            withContext(ioDispatcher) { biometrics.remove(saved.biometric?.alias) }
        } else {
            val seed = pinSeed(pin, ticket)
            val (alias, cipher) = try { withContext(ioDispatcher) { biometrics.enrollment() } }
                catch (e: Exception) {
                    seed.fill(0)
                    if (e is CancellationException) throw e
                    throw IllegalStateException("Не удалось подготовить биометрию. Проверьте отпечаток и блокировку экрана в настройках Android.", e)
                }
            if (session.valid(ticket)) mutablePrompt.value = LockBiometricRequest(ticket, cipher, alias, seed)
            else { seed.fill(0); withContext(ioDispatcher) { biometrics.remove(alias) } }
        }
    } }
    fun biometricUnlock() {
        if (busy || mutablePrompt.value != null || session.phase != LockPhase.LOCKED) return
        val saved = record?.biometric ?: return
        val ticket = session.ticket() ?: return
        busy = true; preferPin = false; error = null; publish()
        scope.launch {
            try {
                val cipher = withContext(ioDispatcher) { biometrics.decrypt(saved) }
                if (session.valid(ticket)) mutablePrompt.value = LockBiometricRequest(ticket, cipher)
            } catch (_: Exception) { preferPin = true; error = "Биометрия недоступна или была изменена. Введите PIN-код." }
            finally { busy = false; publish() }
        }
    }
    fun biometricResult(cipher: Cipher?) {
        val pending = mutablePrompt.value ?: return
        if (cipher !== pending.cipher || !session.valid(pending.id) || busy) return
        mutablePrompt.value = null; busy = true; error = null; publish()
        scope.launch {
            try {
                if (pending.enrollmentAlias != null) {
                    val seal = withContext(ioDispatcher) {
                        val seed = pending.seed
                        if (seed != null) biometrics.sealSeed(pending.enrollmentAlias, pending.cipher, seed)
                        else biometrics.seal(pending.enrollmentAlias, pending.cipher)
                    }
                    if (session.valid(pending.id)) {
                        val old = record?.biometric?.alias
                        persist((record ?: error("Настройки защиты недоступны")).copy(biometric = seal))
                        session.configured(true, pending.id); revision++
                        withContext(ioDispatcher) { biometrics.remove(old) }
                    } else withContext(ioDispatcher) { biometrics.remove(pending.enrollmentAlias) }
                } else if (pending.sensitiveOperation) {
                    val saved = record ?: error("Настройки защиты недоступны")
                    val seed = withContext(ioDispatcher) { biometrics.openSeed(saved.biometric ?: error("Биометрия отключена"), pending.cipher) }
                    if (session.valid(pending.id) && sensitiveResult != null) {
                        biometricSeed = seed
                        if (sensitiveResult?.complete(seed) != true) { seed.fill(0); biometricSeed = null }
                    } else seed.fill(0)
                } else {
                    val saved = record ?: error("Настройки защиты недоступны")
                    check(withContext(ioDispatcher) { biometrics.verify(saved.biometric ?: error("Биометрия отключена"), pending.cipher) })
                    if (session.valid(pending.id)) {
                        persist(saved.successful())
                        if (saved.needsPin) legacyTicket = pending.id else session.unlock(pending.id)
                    }
                }
            } catch (_: Exception) {
                sensitiveResult?.completeExceptionally(IllegalStateException("Не удалось подтвердить биометрию. Введите PIN-код."))
                if (pending.enrollmentAlias != null) withContext(ioDispatcher) { biometrics.remove(pending.enrollmentAlias) }
                preferPin = true; error = if (pending.enrollmentAlias != null) "Не удалось включить биометрию. Повторите подтверждение отпечатком." else "Не удалось подтвердить биометрию. Введите PIN-код."
            } finally { pending.seed?.fill(0); busy = false; publish() }
        }
    }
    fun biometricError(cipher: Cipher, message: String?) {
        if (mutablePrompt.value?.cipher !== cipher) return
        cancelPrompt(); preferPin = true; error = message; publish()
    }
    private fun cancelPrompt() {
        val pending = mutablePrompt.value; mutablePrompt.value = null
        pending?.seed?.fill(0)
        biometricSeed?.fill(0); biometricSeed = null
        activeSeed?.fill(0); activeSeed = null
        sensitiveResult?.cancel(); sensitiveResult = null
        pending?.enrollmentAlias?.let { alias -> scope.launch(ioDispatcher) { biometrics.remove(alias) } }
    }

    private suspend fun pinSeed(pin: String, ticket: Long): ByteArray {
        val saved = record ?: error("Защита приложения отключена")
        require(!saved.needsPin && saved.remainingMillis(System.currentTimeMillis()) == 0L) { "Подождите перед повторным вводом PIN-кода" }
        val chars = pin.toCharArray()
        try {
            val verified = withContext(cryptoDispatcher) { saved.credential.verify(chars) }
            check(session.valid(ticket)) { "Подтверждение отменено" }
            if (!verified) { persist(saved.failed(System.currentTimeMillis())); error("Неверный PIN-код") }
            val seed = withContext(cryptoDispatcher) { saved.sensitiveSeed?.let { seedProtection.unprotect(it).open(chars) } ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) } }
            try {
                val wrapped = withContext(cryptoDispatcher) {
                    seedProtection.protect(saved.sensitiveSeed ?: SensitivePinSeed.wrap(chars, seed))
                }
                persist(saved.successful().copy(sensitiveSeed = wrapped))
                check(session.valid(ticket)) { "Подтверждение отменено" }
                return seed
            } catch (e: Exception) { seed.fill(0); throw e }
        } finally { chars.fill('\u0000'); publish() }
    }

    /** A real decryption gate for each operation; unlocked session alone is insufficient. */
    suspend fun <T> sensitiveOperation(pin: String?, action: suspend (ByteArray) -> T): T {
        check(!busy && activeSeed == null && mutablePrompt.value == null && session.phase == LockPhase.UNLOCKED) { "Разблокируйте приложение" }
        val ticket = session.ticket() ?: error("Приложение свёрнуто")
        val seed = if (pin != null) {
            busy = true; publish()
            try { pinSeed(pin, ticket) } finally { busy = false; publish() }
        } else {
            val saved = record?.biometric ?: error("Биометрия отключена — введите PIN-код")
            val result = CompletableDeferred<ByteArray>(); sensitiveResult = result
            try {
                val cipher = withContext(ioDispatcher) { biometrics.decrypt(saved) }
                check(session.valid(ticket)) { "Подтверждение отменено" }
                mutablePrompt.value = LockBiometricRequest(ticket, cipher, sensitiveOperation = true)
                result.await().also { biometricSeed = null }
            } finally {
                if (mutablePrompt.value?.sensitiveOperation == true) mutablePrompt.value = null
                sensitiveResult = null
                biometricSeed?.fill(0); biometricSeed = null
            }
        }
        return try {
            check(session.valid(ticket)) { "Подтверждение отменено" }
            activeSeed = seed
            action(seed)
        } finally { seed.fill(0); if (activeSeed === seed) activeSeed = null }
    }
    companion object { private const val SLOT = "app_lock" }
}
