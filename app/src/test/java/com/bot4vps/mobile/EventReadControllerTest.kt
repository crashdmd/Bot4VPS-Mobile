package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventReadControllerTest {
    private fun event(id: String, read: Boolean = false) = buildJsonObject { put("id", id); put("read", read) }

    @Test fun openingTheListReadsItsSnapshotWithoutOpeningDetails() = runTest {
        val marked = mutableListOf<String>()
        val controller = EventReadController { marked += it }
        assertEquals(setOf("a", "b", "read"), controller.acknowledge(listOf(event("a"), event("a"), event("b"), event("read", true))))
        assertEquals(setOf("a", "b"), marked.toSet())
        controller.acknowledge(listOf(event("a"), event("b"), event("read", true)))
        assertEquals(2, marked.size)
        assertEquals(setOf("a", "new"), controller.acknowledge(listOf(event("a"), event("new"))))
        assertEquals(listOf("a", "b", "new"), marked)
    }

    @Test fun failedAcknowledgementsStayUnreadAndCanBeRetried() = runTest {
        var fail = true
        val controller = EventReadController { if (it == "b" && fail) error("Offline") }
        val events = listOf(event("a"), event("b"))
        assertEquals(setOf("a"), controller.acknowledge(events))
        fail = false
        assertEquals(setOf("a", "b"), controller.acknowledge(events))
    }

    @Test fun leavingThePageCancelsRequestsAndConcurrencyIsBounded() = runTest {
        var active = 0
        var peak = 0
        val controller = EventReadController {
            active++; peak = maxOf(peak, active)
            try { awaitCancellation() } finally { active-- }
        }
        val work = launch { controller.acknowledge((1..20).map { event("$it") }) }
        runCurrent()
        assertEquals(4, peak)
        work.cancelAndJoin()
        assertEquals(0, active)
    }
}
