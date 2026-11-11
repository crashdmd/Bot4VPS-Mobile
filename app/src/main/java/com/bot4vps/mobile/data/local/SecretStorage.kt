package com.bot4vps.mobile.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Session cookies never leave the device and are encrypted with Android Keystore. */
@Singleton
class SecretStorage @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("bot4vps_secrets", Context.MODE_PRIVATE)

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("bot4vps-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bot4vps-session",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized
    fun read(slot: String = "cookies"): String? {
        return try {
            readStrict(slot)
        } catch (_: Exception) {
            prefs.edit().remove(slot).commit()
            null
        }
    }

    /** Lock configuration must fail closed; a damaged record is never removed. */
    @Synchronized
    fun readStrict(slot: String): String? {
        val stored = prefs.getString(slot, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        if (slot != "cookies") cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized
    fun write(value: String?, slot: String = "cookies") {
        if (value == null) {
            check(prefs.edit().remove(slot).commit()) { "Не удалось сохранить настройки защиты" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        if (slot != "cookies") cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(slot, encoded).commit()) { "Не удалось сохранить сессию" }
    }
}
