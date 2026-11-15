package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.SensitiveKeyStorage
import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SensitiveKeyStorageTest {
    private val origin = "https://panel.test:8444"
    private val other = "https://other.test:8444"
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private fun storage(records: MutableMap<String, String>, beforeWrite: (String?, String) -> Unit = { _, _ -> }) =
        SensitiveKeyStorage(records::get) { value, slot ->
            beforeWrite(value, slot)
            if (value == null) records.remove(slot) else records[slot] = value
        }

    @Test fun returningToThePreviousPortPreservesTheSameKeyAndCanResumeAnApprovedJournal(): Unit = kotlinx.coroutines.runBlocking {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        val key = storage.create(origin, "seed", seed)
        val next = "https://panel.test:885"
        storage.migrate(origin, next); storage.finishMigration(origin, next)
        val returning = MobileConnectionChange(origin, next, "return", "nonce", "pin", "phone", approved = true)
        var journal: MobileConnectionChange? = returning
        com.bot4vps.mobile.data.repository.runConnectionMigration(returning, { journal = it }, { error("Do not confirm an approved request again") },
            { storage.migrate(next, origin) }, {}, {}, { storage.finishMigration(next, origin) })
        assertNull(journal)
        val restored = storage.read(origin, "seed")!!
        assertEquals(key.publicKey, restored.publicKey); assertEquals(key.envelope, restored.envelope)
        restored.sign(seed, origin, "phone", "challenge", "nonce")
        storage.migrate(origin, next); storage.finishMigration(origin, next)
        storage.migrate(next, origin); storage.finishMigration(next, origin)
        assertEquals(key.envelope, storage.read(origin, "seed")!!.envelope)
    }

    @Test fun preflightRejectsAnOccupiedRetiredAddressWithoutWritingAnything() {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        val next = "https://panel.test:885"
        storage.create(origin, "seed", seed)
        storage.migrate(origin, next); storage.finishMigration(origin, next)
        storage.create(origin, "seed", seed)
        val before = records.toMap()
        assertThrows(IllegalStateException::class.java) { storage.validateMigration(next, origin) }
        assertEquals(before, records)
    }

    @Test fun roundTripCanResumeAfterEveryIndividualSecretWrite() {
        val next = "https://panel.test:885"
        // Inject a process/storage failure at each write, including the gap
        // between retiring the old namespace and deleting its old index.
        for (returning in listOf(false, true)) {
            val baseline = mutableMapOf("app_lock" to "unchanged-pin", "totp" to "unchanged-totp")
            val initial = storage(baseline)
            val key = initial.create(origin, "seed", seed)
            val unrelated = initial.create(other, "seed", seed)
            if (returning) { initial.migrate(origin, next); initial.finishMigration(origin, next) }
            val from = if (returning) next else origin
            val to = if (returning) origin else next
            var totalWrites = 0
            val counter = storage(baseline.toMutableMap()) { _, _ -> totalWrites++ }
            counter.migrate(from, to); counter.finishMigration(from, to)
            for (failAt in 1..totalWrites) {
                val records = baseline.toMutableMap()
                var writes = 0
                val interrupted = storage(records) { _, _ -> if (++writes == failAt) error("Simulated crash") }
                assertTrue(runCatching { interrupted.migrate(from, to); interrupted.finishMigration(from, to) }.isFailure)
                val resumed = storage(records)
                resumed.migrate(from, to); resumed.finishMigration(from, to)
                resumed.migrate(from, to); resumed.finishMigration(from, to)
                val moved = resumed.read(to, "seed")!!
                assertEquals(key.publicKey, moved.publicKey); assertEquals(key.envelope, moved.envelope)
                moved.sign(seed, to, "phone", "id", "nonce")
                assertEquals(unrelated, resumed.read(other, "seed"))
                assertEquals("unchanged-pin", records["app_lock"]); assertEquals("unchanged-totp", records["totp"])
                assertNull(resumed.read(from, "seed"))
            }
        }
    }

    @Test fun legacyCompletedMigrationWithoutReceiptResumesAfterDeviceRegistryMoved() {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        val key = storage.create(origin, "seed", seed)
        val next = "https://panel.test:885"
        storage.migrate(origin, next); storage.finishMigration(origin, next)
        records.remove("operation_key_migrations") // The 1.7.4 storage format.
        val before = records.toMap()
        assertThrows(IllegalStateException::class.java) { storage.migrate(origin, next) }
        assertEquals(before, records)
        storage.migrate(origin, next, alreadyMoved = true); storage.finishMigration(origin, next)
        assertEquals(key.envelope, storage.read(next, "seed")!!.envelope)
        storage.migrate(next, origin); storage.finishMigration(next, origin)
        assertEquals(key.publicKey, storage.read(origin, "seed")!!.publicKey)
    }

    @Test fun unaliasedDestinationWithAnExistingKeyIsAlsoRejected() {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        storage.create(origin, "seed", seed); storage.create(other, "seed", seed)
        val before = records.toMap()
        assertThrows(IllegalStateException::class.java) { storage.validateMigration(origin, other) }
        assertEquals(before, records)
    }

    @Test fun forgetRemovesEveryIndexedSeedOfThatPanelAndPreservesOtherSecrets() {
        val records = mutableMapOf("app_lock" to "pin", "cookies" to "session")
        val storage = storage(records)
        storage.create(origin, "old-seed", seed)
        storage.create(origin, "new-seed", seed)
        val otherKey = storage.create(other, "new-seed", seed)
        storage.remove(origin, "new-seed")
        assertNull(storage.read(origin, "old-seed"))
        assertNull(storage.read(origin, "new-seed"))
        assertEquals(otherKey, storage.read(other, "new-seed"))
        assertEquals("pin", records["app_lock"])
        assertEquals("session", records["cookies"])
        storage.remove(origin, "new-seed")
        assertNotEquals(otherKey.publicKey, storage.create(origin, "new-seed", seed).publicKey)
    }

    @Test fun legacyRecordCanBeRemovedWithoutReadingOrDecryptingItsPayload() {
        val slot = "operation_key_" + MessageDigest.getInstance("SHA-256").digest("$origin:legacy".toByteArray()).lockHex()
        val records = mutableMapOf(slot to "damaged encrypted record", "app_lock" to "pin")
        storage(records).remove(origin, "legacy")
        assertEquals(mapOf("app_lock" to "pin"), records)
    }

    @Test fun readingALegacyKeyIndexesItForCleanupAfterASeedChange() {
        val slot = "operation_key_" + MessageDigest.getInstance("SHA-256").digest("$origin:legacy".toByteArray()).lockHex()
        val key = SensitiveOperationKey.create(seed, origin)
        val records = mutableMapOf(slot to Json.encodeToString(key))
        val storage = storage(records)
        assertEquals(key, storage.read(origin, "legacy"))
        storage.remove(origin)
        assertTrue(records.isEmpty())
    }

    @Test fun cleanupFailureKeepsThePairingAndCanBeRetried() {
        val records = mutableMapOf<String, String>()
        var fail = false
        val storage = storage(records) { value, slot ->
            if (fail && value == null && slot.startsWith("operation_key_")) error("Write failed")
        }
        storage.create(origin, "seed", seed)
        val devices = mutableMapOf(origin to PairedMobileDevice("device", "alias", "pin"),
            other to PairedMobileDevice("other", "other-alias", "other-pin"))
        val keys = object : MobileDeviceKeys {
            override fun device(origin: String) = devices[origin]
            override fun publicKey(origin: String): Pair<String, ByteArray> = error("No network")
            override fun operationPublicKey(origin: String): Pair<String, ByteArray> = error("No network")
            override fun remember(origin: String, device: PairedMobileDevice) { devices[origin] = device }
            override fun forget(origin: String) { devices.remove(origin) }
            override fun sign(alias: String, message: ByteArray): ByteArray = error("No network")
        }
        val mobile = MobileAccessClient(keys, Json)
        fail = true
        assertThrows(IllegalStateException::class.java) { mobile.forget("$origin/api/me".toHttpUrl()) { storage.remove(it, "seed") } }
        assertNotNull(devices[origin])
        fail = false
        mobile.forget("$origin/api/me".toHttpUrl()) { storage.remove(it, "seed") }
        assertNull(devices[origin])
        assertNotNull(devices[other])
        assertTrue(records.isEmpty())
    }
    @Test fun addressMigrationPreservesKeyAndEnvelopeAcrossRetriesAndAnotherMove() {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        val key = storage.create(origin, "seed", seed)
        val untouched = storage.create(other, "seed", seed)
        val next = "https://panel.test:9444"
        storage.migrate(origin, next); storage.migrate(origin, next)
        storage.finishMigration(origin, next); storage.migrate(origin, next); storage.finishMigration(origin, next)
        val moved = storage.read(next, "seed")!!
        assertEquals(key.publicKey, moved.publicKey); assertEquals(key.envelope, moved.envelope)
        assertEquals(origin, moved.contextOrigin)
        // The authenticated origin remains valid without decrypting or rotating the key.
        val proof = moved.sign(seed, next, "phone", "id", "nonce")
        val public = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(key.publicKey)))
        assertTrue(java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(public); update("bot4vps-masterkey-view-v1\nphone\nid\nnonce".toByteArray(Charsets.US_ASCII)); verify(java.util.Base64.getDecoder().decode(proof))
        })
        val reconnected = storage.create(origin, "seed", seed)
        assertNotEquals(key.publicKey, reconnected.publicKey)
        assertEquals(key.publicKey, storage.read(next, "seed")!!.publicKey)
        val last = "https://new.test:9444"
        storage.migrate(next, last); storage.finishMigration(next, last)
        assertEquals(key.envelope, storage.read(last, "seed")!!.envelope)
        storage.remove(last, "seed")
        assertNull(storage.read(last, "seed")); assertEquals(untouched, storage.read(other, "seed"))
    }

    @Test fun migratingBeforeFirstSensitiveUseStillSeparatesRepairedOldAddress() {
        val records = mutableMapOf<String, String>()
        val storage = storage(records)
        val next = "https://panel.test:9444"
        storage.migrate(origin, next); storage.finishMigration(origin, next)
        val moved = storage.create(next, "seed", seed)
        val repaired = storage.create(origin, "seed", seed)
        assertNotEquals(moved.publicKey, repaired.publicKey)
        storage.remove(origin, "seed")
        assertEquals(moved, storage.read(next, "seed"))
    }

}
