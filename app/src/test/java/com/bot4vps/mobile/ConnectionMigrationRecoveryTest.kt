package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ConnectionMigrationRecoveryTest {
    private val pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32))
    private val old = "https://panel.test:8444"
    private val pending = MobileConnectionChange("https://panel.test:885", old, "previous", "nonce", pin, "old-device", approved = true)
    private val fresh = PairedMobileDevice("new-device", "retained-key", pin)
    private val profile = PanelProfile(old, "Панель", fresh.id)

    @Test fun obsoleteApprovedTransferIsArchivedAfterRePairWithoutTouchingAnyBinding(): Unit = runBlocking {
        var journal: MobileConnectionChange? = pending
        val unrelated = "https://other.test:8444" to fresh.copy(id = "other-device", alias = "other-key")
        val bindings = listOf(old to fresh, unrelated)
        val saved = listOf(profile, PanelProfile(unrelated.first, "Другая панель", unrelated.second.id))
        val archives = mutableListOf<MobileConnectionChange>()
        assertNull(reconcileConnectionMigration({ journal }, { bindings }, { saved }, { archives += it }, { journal = it }))
        assertNull(journal); assertEquals(listOf(pending), archives)
        assertEquals(fresh, bindings.first().second); assertEquals(2, saved.size)
    }

    @Test fun recoverableAndUnprovenTransfersAreNeverDiscarded(): Unit = runBlocking {
        suspend fun retained(change: MobileConnectionChange = pending,
            bindings: List<Pair<String, PairedMobileDevice>> = listOf(old to fresh),
            saved: List<PanelProfile> = listOf(profile)): MobileConnectionChange? =
            reconcileConnectionMigration({ change }, { bindings }, { saved }, { error("Must retain") }, { error("Must retain") })
        assertEquals(pending, retained(bindings = listOf(old to fresh, pending.origin to fresh.copy(id = pending.deviceId))))
        assertEquals(pending, retained(saved = listOf(profile, profile.copy(url = pending.origin, deviceId = pending.deviceId))))
        assertEquals(pending, retained(bindings = emptyList(), saved = emptyList()))
        assertEquals(pending, retained(bindings = listOf(old to fresh.copy(serverPin = "another-pin"))))
        assertEquals(pending, retained(saved = listOf(profile.copy(revoked = true))))
        val legacy = pending.copy(deviceId = "")
        assertEquals(legacy, retained(change = legacy))
    }

    @Test fun archiveFailureRetainsJournalAndInterruptedCleanupIsRepeatable(): Unit = runBlocking {
        var journal: MobileConnectionChange? = pending
        var archived: MobileConnectionChange? = null
        suspend fun reconcile(failArchive: Boolean = false, failClear: Boolean = false) = reconcileConnectionMigration(
            { journal }, { listOf(old to fresh) }, { listOf(profile) },
            { if (failArchive) error("Write failed"); archived = it },
            { if (failClear) error("Clear failed"); journal = it })
        assertTrue(runCatching { reconcile(failArchive = true) }.isFailure)
        assertEquals(pending, journal); assertNull(archived)
        assertTrue(runCatching { reconcile(failClear = true) }.isFailure)
        assertEquals(pending, journal); assertEquals(pending, archived)
        assertNull(reconcile()); assertNull(journal); assertEquals(pending, archived)
    }

    @Test fun obsoleteUnapprovedTransferCannotConfirmForAReplacementDevice(): Unit = runBlocking {
        val unapproved = pending.copy(approved = false)
        var journal: MobileConnectionChange? = unapproved
        var archived: MobileConnectionChange? = null
        assertNull(reconcileConnectionMigration({ journal }, { listOf(old to fresh) }, { listOf(profile) }, { archived = it }, { journal = it }))
        assertEquals(unapproved, archived); assertNull(journal)
    }

    @Test fun bothScannersDispatchTheActualQrTypeAndNeverPairAnAddressChange(): Unit = runBlocking {
        fun encode(json: String) = "b4vps:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val qr = encode("""{"version":1,"action":"connection_change","url":"https://panel.test:855","old_url":"$old","id":"id","nonce":"nonce","server_pin":"$pin"}""")
        val pairing = encode("""{"version":1,"url":"$old","code":"${"i".repeat(43)}","server_pin":"$pin"}""")
        val failure = assertThrows(MobileAddressChangeRequired::class.java) { readMobileInvitation(qr) }
        assertEquals("https://panel.test:855", failure.change.url)
        val paired = mutableListOf<String>(); val previewed = mutableListOf<String>()
        routePanelConnectionCode(" $qr ", { paired += it }, { previewed += it })
        routePanelConnectionCode(pairing, { paired += it }, { previewed += it })
        assertEquals(listOf(pairing), paired); assertEquals(listOf(qr), previewed)
        assertTrue(runCatching { routePanelConnectionCode(pairing, { error("Must not pair") }, { error("Must not preview") }, allowPairing = false) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { routePanelConnectionCode("b4vps:!", { error("Must not pair") }, { error("Must not preview") }) }.exceptionOrNull() is MobilePairingRequired)
        assertEquals(listOf(pairing), paired); assertEquals(listOf(qr), previewed)
    }
}
