package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class SensitiveOperationKeyTest {
    @Test fun pinEnvelopeRequiresBothTheDeviceKeyAndCorrectPinAndRejectsTampering() {
        val seed = ByteArray(32) { (it + 2).toByte() }
        val pinWrapped = SensitivePinSeed.wrap("0123".toCharArray(), seed)
        val aes = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val encrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, aes) }
        val wrapped = SensitivePinDeviceCipher.protect(pinWrapped, encrypt)
        fun open(value: SensitivePinSeed, key: javax.crypto.SecretKey = aes): SensitivePinSeed = SensitivePinDeviceCipher.unprotect(value,
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, value.envelope.ciphertext.lockBytes().copyOfRange(0, 12)))
            })
        val restored = Json.decodeFromString<SensitivePinSeed>(Json.encodeToString(wrapped))
        assertThrows(Exception::class.java) { restored.open("0123".toCharArray()) }
        assertArrayEquals(seed, open(restored).open("0123".toCharArray()))
        assertThrows(Exception::class.java) { open(restored).open("0124".toCharArray()) }
        assertThrows(Exception::class.java) { open(restored, javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")) }
        assertThrows(Exception::class.java) { open(restored.copy(id = java.util.UUID.randomUUID().toString())) }
        assertThrows(Exception::class.java) { open(restored.copy(salt = ByteArray(32).lockHex())) }
        assertThrows(Exception::class.java) { open(restored.copy(envelope = restored.envelope.copy(iv = ByteArray(12).lockHex()))) }
        val bytes = restored.envelope.ciphertext.lockBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { open(restored.copy(envelope = restored.envelope.copy(ciphertext = bytes.lockHex()))) }
        assertThrows(Exception::class.java) { restored.copy(keystoreWrapped = false).validate() }
        assertThrows(Exception::class.java) { restored.copy(salt = "00").validate() }
    }

    @Test fun proofRequiresThePinAndOriginBoundEncryptedPrivateKey() {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = SensitivePinSeed.wrap("0123".toCharArray(), seed)
        assertThrows(Exception::class.java) { wrapped.open("0124".toCharArray()) }
        val opened = wrapped.open("0123".toCharArray()); assertArrayEquals(seed, opened)
        val origin = "https://panel.test:8444"
        val key = SensitiveOperationKey.create(opened, origin)
        val signature = key.sign(opened, origin, "device", "challenge", "nonce")
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey)))
        fun verify(message: String) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(public); update(message.toByteArray()); verify(Base64.getDecoder().decode(signature))
        }
        assertTrue(verify("bot4vps-masterkey-view-v1\ndevice\nchallenge\nnonce"))
        assertFalse(verify("bot4vps-mobile-v1\ndevice\nchallenge\nnonce"))
        assertThrows(Exception::class.java) { key.sign(opened, "https://other.test:8444", "device", "challenge", "nonce") }
        assertThrows(Exception::class.java) { key.sign(ByteArray(32), origin, "device", "challenge", "nonce") }
        assertFalse(Json.encodeToString(key).contains(seed.lockHex()))
        val changed = SensitivePinSeed.wrap("3456".toCharArray(), seed, wrapped.id)
        assertArrayEquals(seed, changed.open("3456".toCharArray()))
        assertThrows(Exception::class.java) { changed.open("0123".toCharArray()) }
        opened.fill(0); seed.fill(0)
    }
    @Test fun oldBiometricUnlockMarkerCannotAuthorizeSensitiveSigning() {
        val aes = javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
        fun encrypt() = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, aes) }
        fun decrypt(seal: LockBiometricSeal) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, aes, javax.crypto.spec.GCMParameterSpec(128, seal.iv.lockBytes()))
        }
        val old = LockBiometricCipher.seal("bot4vps-lock-test-marker", encrypt())
        assertTrue(LockBiometricCipher.verify(old, decrypt(old)))
        assertThrows(Exception::class.java) { LockBiometricCipher.openSeed(old, decrypt(old)) }
        val seed = ByteArray(32) { it.toByte() }
        val current = LockBiometricCipher.sealSeed("bot4vps-lock-test-marker", encrypt(), seed)
        assertTrue(LockBiometricCipher.verify(current, decrypt(current)))
        assertArrayEquals(seed, LockBiometricCipher.openSeed(current, decrypt(current)))
    }
}
