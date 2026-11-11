package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.MobileWebSocketSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobileWebSocketSessionTest {
    @Test fun proofRenewsOnSameSocketAndAckSchedulesTheNextProof() = runTest {
        val messages = mutableListOf<String>()
        var proofs = 0
        var expired = false
        val lease = MobileWebSocketSession(this, { proofs++; "s".repeat(43) }, { messages.add(it); true }, { expired = true })
        lease.start(900); runCurrent()
        advanceTimeBy(840000); runCurrent()
        assertEquals(1, proofs)
        assertEquals("s".repeat(43), Json.parseToJsonElement(messages.single()).jsonObject["session"]!!.jsonPrimitive.content)
        assertTrue(lease.receive("{\"type\":\"mobile_session\",\"status\":\"renewed\",\"expires_in\":900}"))
        runCurrent(); advanceTimeBy(840000); runCurrent()
        assertEquals(2, proofs); assertFalse(expired)
        lease.stop()
    }

    @Test fun absentAcknowledgementCannotKeepTheConnectionAliveForever() = runTest {
        var closed = false
        val lease = MobileWebSocketSession(this, { "s".repeat(43) }, { true }, { closed = true })
        lease.start(900); runCurrent()
        advanceTimeBy(900000); runCurrent()
        assertTrue(closed); lease.stop()
    }

    @Test fun transientRefreshFailureRetriesWithoutRestartingTerminal() = runTest {
        var attempts = 0
        var sends = 0
        var closed = false
        val lease = MobileWebSocketSession(this,
            { attempts++; if (attempts == 1) error("network"); "s".repeat(43) },
            { sends++; true }, { closed = true })
        lease.start(900); runCurrent(); advanceTimeBy(840000); runCurrent()
        assertEquals(0, sends); assertFalse(closed)
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, sends); assertEquals(2, attempts); assertFalse(closed)
        lease.stop()
    }

    @Test fun closingTheScreenCancelsRefreshAndPreservesNormalTerminalOutput() = runTest {
        var attempts = 0
        val lease = MobileWebSocketSession(this, { attempts++; "s".repeat(43) }, { true }, { error("closed timer") })
        assertFalse(lease.receive("root@server:~# "))
        assertFalse(lease.receive("{\"type\":[]}"))
        lease.start(900); runCurrent(); lease.stop()
        advanceTimeBy(1800000); runCurrent()
        assertEquals(0, attempts)
    }
}
