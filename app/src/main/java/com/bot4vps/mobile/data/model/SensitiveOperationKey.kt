package com.bot4vps.mobile.data.model

import kotlinx.serialization.Serializable
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun masterKeyEnrollmentMessage(device: String, challenge: String, nonce: String, publicKey: String, deviceOperationPublicKey: String): ByteArray {
    require(listOf(device, challenge, nonce).all { it.isNotBlank() && '\n' !in it && it.length <= 128 })
    require(publicKey.isNotBlank() && '\n' !in publicKey && publicKey.length <= 1024)
    require(deviceOperationPublicKey.isNotBlank() && '\n' !in deviceOperationPublicKey && deviceOperationPublicKey.length <= 1024)
    return "bot4vps-masterkey-enroll-v1\n$device\n$challenge\n$nonce\n$publicKey\n$deviceOperationPublicKey".toByteArray(Charsets.US_ASCII)
}

/** Envelopes are also wrapped by SecretStorage's Android Keystore key. */
@Serializable
data class SensitiveEnvelope(val iv: String, val ciphertext: String) {
    fun validate() {
        require(iv.length == 24 && iv.lockBytes().size == 12)
        require(ciphertext.length in 32..4096 && ciphertext.lockBytes().size >= 16)
    }
    fun open(key: ByteArray, context: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        this@SensitiveEnvelope.validate(); require(key.size == 32)
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, this@SensitiveEnvelope.iv.lockBytes()))
        updateAAD(context.toByteArray()); doFinal(ciphertext.lockBytes())
    }
    companion object {
        fun seal(key: ByteArray, value: ByteArray, context: String): SensitiveEnvelope = Cipher.getInstance("AES/GCM/NoPadding").run {
            require(key.size == 32 && value.size <= 2032)
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(context.toByteArray())
            SensitiveEnvelope(iv.lockHex(), doFinal(value).lockHex())
        }
    }
}

@Serializable
data class SensitivePinSeed(val salt: String, val envelope: SensitiveEnvelope, val id: String,
    val keystoreWrapped: Boolean = false) {
    fun validate() {
        require(id.length == 36 && java.util.UUID.fromString(id).toString() == id)
        require(salt.length == 64 && salt.lockBytes().size == 32)
        envelope.validate()
        require(envelope.ciphertext.length == if (keystoreWrapped) 152 else 96)
    }
    fun open(pin: CharArray): ByteArray {
        validate(); check(!keystoreWrapped) { "Сначала откройте защиту Android Keystore" }
        return derive(pin, salt.lockBytes()).let { key ->
            try { envelope.open(key, "bot4vps-sensitive-seed-v1:$id") } finally { key.fill(0) }
        }
    }
    companion object {
        private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(pin, salt, LockPin.ITERATIONS, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        }
        fun wrap(pin: CharArray, seed: ByteArray, id: String = java.util.UUID.randomUUID().toString()): SensitivePinSeed {
            require(seed.size == 32)
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val key = derive(pin, salt)
            return try { SensitivePinSeed(salt.lockHex(), SensitiveEnvelope.seal(key, seed, "bot4vps-sensitive-seed-v1:$id"), id) }
            finally { key.fill(0) }
        }
    }
}

/** The PIN-derived envelope remains encrypted behind a separate device key. */
object SensitivePinDeviceCipher {
    private fun context(seed: SensitivePinSeed) =
        "bot4vps-pin-keystore-v1:${seed.id}:${seed.salt}:${seed.envelope.iv}".toByteArray(Charsets.US_ASCII)
    fun protect(seed: SensitivePinSeed, cipher: Cipher): SensitivePinSeed {
        seed.validate(); require(!seed.keystoreWrapped)
        cipher.updateAAD(context(seed))
        val bytes = seed.envelope.ciphertext.lockBytes()
        val encrypted = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        return seed.copy(envelope = seed.envelope.copy(ciphertext = (cipher.iv + encrypted).lockHex()), keystoreWrapped = true)
            .also(SensitivePinSeed::validate)
    }
    fun unprotect(seed: SensitivePinSeed, cipher: Cipher): SensitivePinSeed {
        seed.validate(); require(seed.keystoreWrapped)
        cipher.updateAAD(context(seed))
        val bytes = seed.envelope.ciphertext.lockBytes()
        val opened = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { seed.copy(envelope = seed.envelope.copy(ciphertext = opened.lockHex()), keystoreWrapped = false).also(SensitivePinSeed::validate) }
        finally { opened.fill(0) }
    }
}

@Serializable
data class SensitiveOperationKey(val publicKey: String, val envelope: SensitiveEnvelope) {
    fun sign(seed: ByteArray, origin: String, device: String, challenge: String, nonce: String): String {
        require(listOf(device, challenge, nonce).all { it.isNotBlank() && '\n' !in it && it.length <= 128 })
        return signMessage(seed, origin, "bot4vps-masterkey-view-v1\n$device\n$challenge\n$nonce".toByteArray(Charsets.US_ASCII))
    }
    fun signEnrollment(seed: ByteArray, origin: String, device: String, challenge: String, nonce: String, deviceOperationPublicKey: String): String =
        signMessage(seed, origin, masterKeyEnrollmentMessage(device, challenge, nonce, publicKey, deviceOperationPublicKey))

    private fun signMessage(seed: ByteArray, origin: String, message: ByteArray): String {
        val bytes = envelope.open(seed, "bot4vps-sensitive-key-v1:$origin:$publicKey")
        return try {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            try {
                Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() })
            } finally { runCatching { key.destroy() } }
        } finally { bytes.fill(0) }
    }
    companion object {
        fun create(seed: ByteArray, origin: String): SensitiveOperationKey {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val public = Base64.getEncoder().encodeToString(pair.public.encoded)
            val bytes = pair.private.encoded
            return try { SensitiveOperationKey(public, SensitiveEnvelope.seal(seed, bytes, "bot4vps-sensitive-key-v1:$origin:$public")) }
            finally { bytes.fill(0); runCatching { pair.private.destroy() } }
        }
    }
}
