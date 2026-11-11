package com.bot4vps.mobile.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bot4vps.mobile.data.model.SensitivePinDeviceCipher
import com.bot4vps.mobile.data.model.SensitivePinSeed
import com.bot4vps.mobile.data.model.lockBytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface AppLockSeedProtection {
    fun protect(seed: SensitivePinSeed): SensitivePinSeed
    fun unprotect(seed: SensitivePinSeed): SensitivePinSeed
    fun remove(id: String)
}

/** App PIN is separate from Android credentials. Never exports this wrapping key. */
@Singleton
class AndroidAppLockSeedProtection @Inject constructor() : AppLockSeedProtection {
    private fun alias(id: String): String {
        require(java.util.UUID.fromString(id).toString() == id)
        return "bot4vps-pin-seed-$id"
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(id: String, create: Boolean): SecretKey {
        val name = alias(id)
        (store().getKey(name, null) as? SecretKey)?.let { return it }
        check(create) { "Ключ защиты PIN недоступен. Используйте Web с вторым фактором или CLI." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized
    override fun protect(seed: SensitivePinSeed): SensitivePinSeed {
        seed.validate()
        if (seed.keystoreWrapped) return seed
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(seed.id, true)) }
        return SensitivePinDeviceCipher.protect(seed, cipher)
    }

    @Synchronized
    override fun unprotect(seed: SensitivePinSeed): SensitivePinSeed {
        seed.validate()
        if (!seed.keystoreWrapped) return seed // Migrate only after successful PIN authentication.
        val bytes = seed.envelope.ciphertext.lockBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(seed.id, false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return SensitivePinDeviceCipher.unprotect(seed, cipher)
    }

    @Synchronized
    override fun remove(id: String) { store().deleteEntry(alias(id)) }
}
