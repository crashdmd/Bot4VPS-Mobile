package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveKeyStorage @Inject constructor(private val secrets: SecretStorage) {
    private fun slot(origin: String, seedId: String) = "operation_key_" +
        MessageDigest.getInstance("SHA-256").digest("$origin:$seedId".toByteArray()).lockHex()
    fun read(origin: String, seedId: String): SensitiveOperationKey? =
        secrets.readStrict(slot(origin, seedId))?.let { Json.decodeFromString<SensitiveOperationKey>(it) }
    fun create(origin: String, seedId: String, seed: ByteArray): SensitiveOperationKey =
        read(origin, seedId) ?: SensitiveOperationKey.create(seed, origin).also {
            secrets.write(Json.encodeToString(it), slot(origin, seedId))
        }
}
