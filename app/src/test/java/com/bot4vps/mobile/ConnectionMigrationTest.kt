package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.runConnectionMigration
import com.bot4vps.mobile.data.repository.resumeApprovedConnectionMigration
import com.bot4vps.mobile.data.repository.validateConnectionMigrationProfiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ConnectionMigrationTest {
    private val pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32))
    private val change = MobileConnectionChange("https://new.test:9444", "https://old.test:8444", "id", "nonce", pin, "phone")
    @Test fun manualAddressAssumesHttpsWithoutWeakeningQrValidation() {
        assertEquals("https://panel.test:8444", manualConnectionUrl("panel.test:8444"))
        assertEquals("https://192.0.2.1:885", manualConnectionUrl(" 192.0.2.1:885 "))
        assertEquals("https://panel.test:443", manualConnectionUrl("panel.test"))
        for (value in listOf("http://panel.test", "user:password@panel.test", "panel.test/path", "panel.test?key=1", "")) {
            assertTrue(value, runCatching { manualConnectionUrl(value) }.isFailure)
        }
        assertTrue(runCatching { connectionUrl("panel.test:8444") }.isFailure)
    }
    @Test fun proofMatchesServerAndQrRejectsForeignActionsAndUnsafeAddresses() {
        assertArrayEquals("bot4vps-mobile-connection-v1\nid\nnonce\nphone\nhttps://new.test:9444".toByteArray(Charsets.US_ASCII), connectionChangeMessage(change, "phone"))
        fun qr(url: String, action: String = "connection_change") = "b4vps:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"version":1,"action":"$action","url":"$url","old_url":"${change.oldUrl}","id":"id","nonce":"nonce","server_pin":"$pin"}""".toByteArray())
        assertEquals(change.copy(deviceId = ""), readConnectionChange(qr(change.url)))
        for (url in listOf("http://new.test", "https://user:pass@new.test", "https://new.test/path", "https://new.test?key=1", "https://new.test#fragment")) {
            assertTrue(url, runCatching { readConnectionChange(qr(url)) }.isFailure)
        }
        assertTrue(runCatching { readConnectionChange(qr(change.url, "pair")) }.isFailure)
        assertTrue(runCatching { connectionChangeMessage(change.copy(nonce = "x\nother"), "phone") }.isFailure)
    }
    @Test fun preflightConflictsBlockRemoteApprovalAndPostApprovalFailureRetainsTheJournal(): Unit = runBlocking {
        for (approved in listOf(false, true)) {
            var confirmations = 0; var moves = 0; var writes = 0
            assertTrue(runCatching { runConnectionMigration(change.copy(approved = approved), { writes++ }, { confirmations++ },
                { moves++ }, { moves++ }, { moves++ }, { moves++ }, preflight = { error("Local conflict") }) }.isFailure)
            assertEquals(0, confirmations); assertEquals(0, moves); assertEquals(0, writes)
        }
        var journal: MobileConnectionChange? = change
        var confirmations = 0; var moves = 0; var validations = 0
        assertTrue(runCatching { runConnectionMigration(change, { journal = it }, { confirmations++ },
            { moves++ }, { moves++ }, { moves++ }, { moves++ }, preflight = { if (++validations == 2) error("Conflict during remote confirmation") }) }.isFailure)
        assertTrue(journal!!.approved); assertEquals(1, confirmations); assertEquals(0, moves)
        runConnectionMigration(journal!!, { journal = it }, { confirmations++ }, {}, {}, {}, {})
        assertNull(journal); assertEquals(1, confirmations)
    }

    @Test fun remoteFailureCannotMoveAnyLocalData(): Unit = runBlocking {
        var moved = 0
        var journal: MobileConnectionChange? = change
        assertTrue(runCatching { runConnectionMigration(change, { journal = it }, { error("timeout") }, { moved++ }, { moved++ }, { moved++ }, { moved++ }) }.isFailure)
        assertEquals(0, moved); assertEquals(change, journal)
    }
    @Test fun approvedOldJournalPublishesTheProfileBeforeANewQrIsRead(): Unit = runBlocking {
        var journal: MobileConnectionChange? = change.copy(approved = true)
        var selected = change.oldOrigin
        var remoteConfirmations = 0
        val recovered = resumeApprovedConnectionMigration({ journal }) { pending ->
            runConnectionMigration(pending, { journal = it }, { remoteConfirmations++ }, {}, {}, {}, { selected = pending.origin })
        }
        assertEquals(change.origin, selected); assertNull(journal); assertEquals(0, remoteConfirmations)
        assertEquals(change.id, recovered!!.id)
        val next = MobileConnectionChange("https://new.test:855", selected, "second", "nonce", pin, "phone")
        assertNotEquals(recovered.id, next.id) // The recovered request cannot consume a new confirmation.
        assertNull(resumeApprovedConnectionMigration({ journal }) { error("Nothing to recover") })
    }
    @Test fun failedOldRecoveryBlocksTheNewQrAndKeepsTheJournal(): Unit = runBlocking {
        val pending = change.copy(approved = true)
        assertTrue(runCatching { resumeApprovedConnectionMigration({ pending }) { error("storage failure") } }.isFailure)
        assertTrue(runCatching { resumeApprovedConnectionMigration({ pending }) {} }.isFailure)
        val unapproved = change.copy(approved = false)
        assertNull(resumeApprovedConnectionMigration({ unapproved }) { error("Preview must not approve an older request") })
    }
    @Test fun legacyJournalAndLocalSourceSurviveSerialization() {
        val json = kotlinx.serialization.json.Json
        val legacy = json.decodeFromString<MobileConnectionChange>(json.encodeToString(MobileConnectionChange.serializer(), change))
        assertEquals(change.oldOrigin, legacy.sourceOrigin); assertEquals(change.oldOrigin, legacy.profileOrigin)
        val moved = change.copy(localOldUrl = "https://old.test:885", profileOldUrl = change.oldUrl)
        val saved = json.decodeFromString<MobileConnectionChange>(json.encodeToString(MobileConnectionChange.serializer(), moved))
        assertEquals(moved, saved); assertNotEquals(saved.oldOrigin, saved.sourceOrigin)
        assertArrayEquals(connectionChangeMessage(change, "phone"), connectionChangeMessage(saved, "phone"))
    }
    @Test fun everyProfileWriteConflictIsRejectedBeforeRemoteConfirmation() {
        val transfer = change.copy(localOldUrl = "https://old.test:885", profileOldUrl = change.oldUrl)
        for (address in setOf(transfer.sourceOrigin, transfer.profileOrigin, transfer.origin)) {
            assertThrows(IllegalStateException::class.java) {
                validateConnectionMigrationProfiles(listOf(PanelProfile(address, "Другой сервис", "other-phone")), transfer, "phone")
            }
        }
        validateConnectionMigrationProfiles(listOf(PanelProfile(transfer.sourceOrigin, "Панель", "phone"),
            PanelProfile("https://unrelated.test:855", "Другая панель", "other-phone")), transfer, "phone")
    }
    @Test fun everyStorageFailureRetainsAnApprovedJournalAndCanBeRecoveredWithoutAnotherApproval(): Unit = runBlocking {
        for (failure in 0..3) {
            var journal: MobileConnectionChange? = change
            var confirmations = 0
            var fail = true
            val done = mutableSetOf<Int>()
            suspend fun step(index: Int) { if (fail && index == failure) error("storage failed"); done += index }
            suspend fun run(candidate: MobileConnectionChange) = runConnectionMigration(candidate, { journal = it }, { confirmations++ }, { step(0) }, { step(1) }, { step(2) }, { step(3) })
            assertTrue(runCatching { run(change) }.isFailure)
            assertTrue(journal!!.approved); assertEquals(1, confirmations)
            fail = false; run(journal!!)
            assertEquals(setOf(0,1,2,3), done); assertNull(journal); assertEquals(1, confirmations)
        }
    }
    @Test fun choosingAnotherServiceNeedsAnAvailableAlternativeAndTreatsImplicit443AsCurrent() {
        val current = PanelProfile("https://panel.test:443", "Панель", "phone")
        assertFalse(hasOtherService(emptyList(), null))
        assertFalse(hasOtherService(listOf(current), "https://panel.test/"))
        assertEquals("https://panel.test:443", panelProfileOrigin("https://panel.test/"))
        val other = PanelProfile("https://other.test:8444", "Другая", "other")
        assertFalse(hasOtherService(listOf(current, other.copy(revoked = true)), "https://panel.test/"))
        assertFalse(hasOtherService(listOf(current, other.copy(localDisconnected = true)), "https://panel.test/"))
        assertTrue(hasOtherService(listOf(current, other), "https://panel.test/"))
    }

}
