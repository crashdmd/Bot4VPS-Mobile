package com.bot4vps.mobile.data.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.Cipher
import javax.crypto.spec.PBEKeySpec

enum class LockPhase { LOADING, DISABLED, LOCKED, UNLOCKED, UNAVAILABLE }

/** Foreground authentication is deliberately never persisted or put in saved instance state. */
class LockSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var backgroundAt: Long? = null
    var phase = LockPhase.LOADING; private set
    var foreground = false; private set
    var generation = 0L; private set
    fun loaded(enabled: Boolean) { generation++; phase = if (enabled) LockPhase.LOCKED else LockPhase.DISABLED }
    fun unavailable() { generation++; phase = LockPhase.UNAVAILABLE }
    fun foreground() { expireBackground(); foreground = true; backgroundAt = null }
    fun background() {
        if (!foreground) return
        foreground = false; generation++; backgroundAt = clock()
    }
    fun expireBackground() {
        if (!foreground && backgroundAt?.let { clock() - it >= BACKGROUND_LOCK_DELAY } == true) lock()
    }
    fun lock() { generation++; if (phase == LockPhase.UNLOCKED) phase = LockPhase.LOCKED }
    fun ticket(): Long? = generation.takeIf { foreground && phase in setOf(LockPhase.LOCKED, LockPhase.UNLOCKED, LockPhase.DISABLED) }
    fun valid(ticket: Long): Boolean = foreground && generation == ticket && phase in setOf(LockPhase.LOCKED, LockPhase.UNLOCKED, LockPhase.DISABLED)
    fun unlock(ticket: Long): Boolean {
        if (!valid(ticket) || phase != LockPhase.LOCKED) return false
        generation++; phase = LockPhase.UNLOCKED; return true
    }
    fun configured(enabled: Boolean, authenticatedTicket: Long? = null) {
        val authenticated = authenticatedTicket?.let(::valid) == true
        generation++
        phase = if (!enabled) LockPhase.DISABLED else if (authenticated) LockPhase.UNLOCKED else LockPhase.LOCKED
    }
    companion object { const val BACKGROUND_LOCK_DELAY = 30_000L }
}

fun ByteArray.lockHex(): String = joinToString("") { "%02x".format(it) }
fun String.lockBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' })
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Serializable
data class LockPin(val salt: String, val digest: String, val iterations: Int = ITERATIONS) {
    fun validate() { require(iterations == ITERATIONS && salt.lockBytes().size == 32 && digest.lockBytes().size == 32) }
    fun verify(password: CharArray): Boolean {
        validate()
        val derived = derive(password, salt.lockBytes(), iterations)
        return try { MessageDigest.isEqual(digest.lockBytes(), derived) } finally { derived.fill(0) }
    }
    companion object {
        const val ITERATIONS = 600_000
        const val LENGTH = 4
        fun create(password: CharArray): LockPin {
            require(password.size == LENGTH && password.all { it in '0'..'9' }) { "PIN-код должен содержать 4 цифры" }
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val derived = derive(password, salt, ITERATIONS)
            return try { LockPin(salt.lockHex(), derived.lockHex()) } finally { derived.fill(0) }
        }
        private fun derive(password: CharArray, salt: ByteArray, rounds: Int): ByteArray {
            val spec = PBEKeySpec(password, salt, rounds, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
    }
}

@Serializable
data class LockBiometricSeal(val alias: String, val iv: String, val ciphertext: String) {
    fun validate() {
        require(alias.startsWith("bot4vps-lock-") && alias.length in 20..80)
        require(iv.lockBytes().size == 12 && ciphertext.lockBytes().size in 17..256)
    }
}

/** Called only after BiometricPrompt authorizes this cipher's Keystore operation. */
object LockBiometricCipher {
    private val marker = "Bot4VPS local biometric unlock v1".toByteArray(Charsets.UTF_8)
    fun seal(alias: String, cipher: Cipher): LockBiometricSeal {
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return LockBiometricSeal(alias, cipher.iv.lockHex(), cipher.doFinal(marker).lockHex())
    }
    fun sealSeed(alias: String, cipher: Cipher, seed: ByteArray): LockBiometricSeal {
        require(seed.size == 32)
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val payload = marker + seed
        return try { LockBiometricSeal(alias, cipher.iv.lockHex(), cipher.doFinal(payload).lockHex()) }
        finally { payload.fill(0) }
    }
    fun openSeed(seal: LockBiometricSeal, cipher: Cipher): ByteArray {
        cipher.updateAAD(seal.alias.toByteArray(Charsets.UTF_8))
        val payload = cipher.doFinal(seal.ciphertext.lockBytes())
        return try {
            require(payload.size == marker.size + 32 && MessageDigest.isEqual(marker, payload.copyOfRange(0, marker.size))) { "Обновите настройку биометрии с PIN-кодом" }
            payload.copyOfRange(marker.size, payload.size)
        } finally { payload.fill(0) }
    }
    fun verify(seal: LockBiometricSeal, cipher: Cipher): Boolean {
        cipher.updateAAD(seal.alias.toByteArray(Charsets.UTF_8))
        val decrypted = cipher.doFinal(seal.ciphertext.lockBytes())
        return try { (decrypted.size == marker.size || decrypted.size == marker.size + 32) &&
            MessageDigest.isEqual(marker, decrypted.copyOfRange(0, marker.size)) } finally { decrypted.fill(0) }
    }
}

@Serializable
data class AppLockRecord(
    val pin: LockPin? = null,
    val password: LockPin? = null,
    val biometric: LockBiometricSeal? = null,
    val failedAttempts: Int = 0,
    val retryAt: Long = 0,
    val version: Int = 2,
    val sensitiveSeed: SensitivePinSeed? = null,
) {
    val needsPin get() = version == 1
    val credential get() = pin ?: password ?: error("PIN-код недоступен")
    fun validate() {
        require(failedAttempts in 0..100_000 && retryAt >= 0)
        require((version == 1 && password != null && pin == null) || (version == 2 && pin != null && password == null))
        credential.validate(); biometric?.validate(); sensitiveSeed?.validate()
    }
    fun remainingMillis(now: Long): Long = (retryAt - now).coerceIn(0, 300_000)
    fun failed(now: Long): AppLockRecord {
        val attempts = (failedAttempts + 1).coerceAtMost(100_000)
        val wait = if (attempts < 5) 0L else (30_000L shl ((attempts - 5) / 5).coerceAtMost(4)).coerceAtMost(300_000L)
        return copy(failedAttempts = attempts, retryAt = if (wait == 0L) 0 else now + wait)
    }
    fun successful(): AppLockRecord = copy(failedAttempts = 0, retryAt = 0)
}

/** Old records omitted version=1; never turn existing protection off during an upgrade. */
fun decodeAppLockRecord(raw: String): AppLockRecord {
    val json = kotlinx.serialization.json.Json
    val item = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: error("Настройки защиты повреждены")
    val legacy = "pin" !in item && "password" in item
    return json.decodeFromString<AppLockRecord>(raw).let { saved ->
        (if (legacy && "version" !in item) saved.copy(version = 1) else saved).also(AppLockRecord::validate)
    }
}
