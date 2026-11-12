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
}
