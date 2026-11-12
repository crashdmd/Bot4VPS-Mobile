package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveKeyStorage internal constructor(private val readSecret: (String) -> String?,
    private val writeSecret: (String?, String) -> Unit) {
    @Inject constructor(secrets: SecretStorage) : this(secrets::readStrict, secrets::write)

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).lockHex()
    private fun slot(origin: String, seedId: String) = "operation_key_" + hash("$origin:$seedId")
    private fun index(origin: String) = "operation_key_slots_" + hash(origin)
    private fun slots(origin: String): Set<String> = readSecret(index(origin))?.let {
        Json.decodeFromString<Set<String>>(it).also { entries ->
            require(entries.all { name -> name.matches(Regex("operation_key_[0-9a-f]{64}")) })
        }
    }.orEmpty()
    private fun remember(origin: String, slot: String) {
        val entries = slots(origin)
        if (slot !in entries) writeSecret(Json.encodeToString(entries + slot), index(origin))
    }

    @Synchronized
    fun read(origin: String, seedId: String): SensitiveOperationKey? =
        readSecret(slot(origin, seedId))?.let {
            Json.decodeFromString<SensitiveOperationKey>(it).also { remember(origin, slot(origin, seedId)) }
        }

    @Synchronized
    fun create(origin: String, seedId: String, seed: ByteArray): SensitiveOperationKey =
        read(origin, seedId) ?: SensitiveOperationKey.create(seed, origin).also {
            val slot = slot(origin, seedId)
            // Keep the index first so interrupted writes can still be cleaned up.
            remember(origin, slot)
            writeSecret(Json.encodeToString(it), slot)
        }

    @Synchronized
    fun remove(origin: String, seedId: String? = null) {
        // The current seed also locates a record created before the index existed.
        val entries = slots(origin) + listOfNotNull(seedId?.let { slot(origin, it) })
        entries.forEach { writeSecret(null, it) }
        writeSecret(null, index(origin))
    }
}
