package com.bot4vps.mobile.data.local

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bot4vps.mobile.data.model.LockBiometricSeal
import com.bot4vps.mobile.data.model.LockBiometricCipher
import com.bot4vps.mobile.data.model.lockBytes
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockBiometrics @Inject constructor() : AppLockBiometricOperations {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun enrollment(): Pair<String, Cipher> {
        val alias = "bot4vps-lock-${UUID.randomUUID()}"
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
        return try { alias to Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) } }
        catch (e: Exception) { remove(alias); throw e }
    }
    override fun seal(alias: String, cipher: Cipher): LockBiometricSeal = LockBiometricCipher.seal(alias, cipher)
    override fun decrypt(seal: LockBiometricSeal): Cipher {
        seal.validate()
        val key = store().getKey(seal.alias, null) as? SecretKey ?: error("Ключ биометрии недоступен")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, seal.iv.lockBytes()))
        }
    }
    override fun verify(seal: LockBiometricSeal, cipher: Cipher): Boolean = LockBiometricCipher.verify(seal, cipher)
    override fun remove(alias: String?) { if (alias?.startsWith("bot4vps-lock-") == true) runCatching { store().deleteEntry(alias) } }
}
