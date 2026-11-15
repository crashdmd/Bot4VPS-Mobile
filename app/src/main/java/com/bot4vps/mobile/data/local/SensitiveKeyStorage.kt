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
    private fun aliases(): Map<String, String> = readSecret("operation_key_origins")?.let { Json.decodeFromString(it) } ?: emptyMap()
    private fun migrations(): Map<String, String> = readSecret("operation_key_migrations")?.let { Json.decodeFromString(it) } ?: emptyMap()
    private fun migrationId(oldOrigin: String, newOrigin: String) = "$oldOrigin\n$newOrigin"
    private fun storageOrigin(origin: String) = aliases()[origin] ?: origin
    private fun slot(origin: String, seedId: String) = "operation_key_" + hash("${storageOrigin(origin)}:$seedId")
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
        val namespace = aliases()[origin]
        val remaining = if (namespace?.startsWith("retired:") == true) aliases() else aliases() - origin
        writeSecret(if (remaining.isEmpty()) null else Json.encodeToString(remaining), "operation_key_origins")
    }

    @Synchronized
    fun validateMigration(oldOrigin: String, newOrigin: String, alreadyMoved: Boolean = false) {
        if (oldOrigin == newOrigin) return
        val source = storageOrigin(oldOrigin)
        val destination = aliases()[newOrigin]
        val sourceEntries = slots(oldOrigin)
        val destinationEntries = slots(newOrigin)
        val receipt = migrations()[migrationId(oldOrigin, newOrigin)]
        val vacantNamespace = destination == null || destination.startsWith("retired:")
        // The destination index may already have been committed before a crash.
        // It is reusable only when the receipt and every indexed slot belong
        // to this migration, or when the destination is entirely empty.
        val vacant = vacantNamespace && (destinationEntries.isEmpty() ||
            receipt == source && destinationEntries.all { it in sourceEntries })
        val completed = source.startsWith("retired:") && source != destination && destination != null &&
            sourceEntries.all { it in destinationEntries } && (alreadyMoved || receipt == destination)
        check(completed || vacant || destination == source) { "На новом адресе уже есть другой ключ просмотра" }
    }

    @Synchronized
    fun migrate(oldOrigin: String, newOrigin: String, alreadyMoved: Boolean = false) {
        if (oldOrigin == newOrigin) return
        val source = storageOrigin(oldOrigin)
        val destination = aliases()[newOrigin]
        validateMigration(oldOrigin, newOrigin, alreadyMoved)
        if (source.startsWith("retired:") && source != destination && destination != null && slots(oldOrigin).all { it in slots(newOrigin) } &&
            (alreadyMoved || migrations()[migrationId(oldOrigin, newOrigin)] == destination)) return
        val entries = slots(oldOrigin) + slots(newOrigin)
        writeSecret(Json.encodeToString(migrations() + (migrationId(oldOrigin, newOrigin) to source)), "operation_key_migrations")
        entries.forEach { name -> readSecret(name)?.let { raw ->
            val key = Json.decodeFromString<SensitiveOperationKey>(raw)
            writeSecret(Json.encodeToString(key.copy(contextOrigin = key.contextOrigin ?: oldOrigin)), name)
        } }
        // Preserve the envelope's authenticated origin and key bytes. The seed
        // need not be unlocked; moving an address must never rotate this key.
        writeSecret(Json.encodeToString(entries), index(newOrigin))
        writeSecret(Json.encodeToString(aliases() + (newOrigin to source)), "operation_key_origins")
    }

    @Synchronized
    fun finishMigration(oldOrigin: String, newOrigin: String) {
        if (oldOrigin == newOrigin) return
        check(aliases()[newOrigin] != null)
        // The moved envelope still lives in its original opaque slots. Give
        // the now-vacant old address a fresh namespace so a later re-pairing
        // cannot accidentally reuse the moved panel's sensitive key.
        if (storageOrigin(oldOrigin) == aliases()[newOrigin])
            writeSecret(Json.encodeToString(aliases() + (oldOrigin to ("retired:" + java.util.UUID.randomUUID()))), "operation_key_origins")
        writeSecret(null, index(oldOrigin))
    }
}
