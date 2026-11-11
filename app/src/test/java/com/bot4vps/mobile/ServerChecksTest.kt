package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerChecksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun report(kind: ServerCheckKind, rows: List<ServerCheckRow> = emptyList()) =
        ServerCheckReport(ok = true, kind = kind.id, servers = rows)

    @Test fun pingTimeoutIsNotReportedAsLatencyAndZeroIsValid() {
        assertEquals(0.0, json.decodeFromString<ServerPing>("""{"ok":true,"ms":0}""").latencyMs!!, 0.0)
        assertNull(json.decodeFromString<ServerPing>("""{"ok":false,"ms":2000,"error":"timeout"}""").latencyMs)
        assertNull(ServerPing(ok = true, ms = Double.NaN).latencyMs)
        assertNull(ServerPing(ok = true, ms = -1.0).latencyMs)
        assertNull(ServerPing(error = "Сессия недоступна").latencyMs)
    }

    @Test fun bulkResultsKeepOfflineServersSkippedCertificatesAndErrors() {
        val online = ServerCheckReport.parse(json.parseToJsonElement("""{"ok":true,"kind":"online","servers":[
            {"id":"a","name":"A","online":true,"status":"online","ms":12.5,"method":"TCP"},
            {"id":"b","online":false,"status":"offline","ms":2000,"error":"timeout"},
            {"id":"c","status":"unknown","online":null,"ms":null}]}""").jsonObject, ServerCheckKind.ONLINE, json)
        assertEquals(3, online.servers.size)
        assertEquals(12.5, online.servers[0].latencyMs!!, 0.0)
        assertNull(online.servers[1].latencyMs)
        assertEquals("timeout", online.servers[1].error)
        assertNull(online.servers[2].available)
        val ssl = ServerCheckReport.parse(json.parseToJsonElement("""{"ok":true,"kind":"ssl","servers":[
            {"id":"a","status":"expired","days_left":-2,"expires":"2026-11-07","checked":"2026-11-09"},
            {"id":"b","status":"skip","days_left":null},
            {"id":"c","status":"error","error":"TLS handshake failed"}]}""").jsonObject, ServerCheckKind.SSL, json)
        assertEquals(-2, ssl.servers[0].daysLeft)
        assertEquals("skip", ssl.servers[1].status)
        assertEquals("TLS handshake failed", ssl.servers[2].error)
        assertEquals(listOf("a", "c"), ssl.visibleServers(ServerCheckKind.SSL).map { it.id })
        assertEquals(online.servers, online.visibleServers(ServerCheckKind.ONLINE))
        assertTrue(report(ServerCheckKind.SSL, listOf(ServerCheckRow("skip", status = "skip")))
            .visibleServers(ServerCheckKind.SSL).isEmpty())
    }

    @Test fun malformedAndWrongKindRepliesAreRejected() {
        for (raw in listOf("""{"ok":true,"kind":"online"}""", """{"ok":true,"kind":"ssl","servers":[]}""",
            """{"ok":false,"kind":"online","servers":[]}""", """{"ok":true,"kind":"online","servers":[{"id":"a"},{"id":"a"}]}""")) {
            assertTrue(runCatching { ServerCheckReport.parse(json.parseToJsonElement(raw).jsonObject, ServerCheckKind.ONLINE, json) }.isFailure)
        }
        assertTrue(ServerCheckReport.parse(json.parseToJsonElement("""{"ok":true,"kind":"online","servers":[]}""").jsonObject,
            ServerCheckKind.ONLINE, json).servers.isEmpty())
    }

    @Test fun duplicateClicksDoNotResubmitAndEachTabRetainsItsOwnResults() = runTest {
        val online = CompletableDeferred<ServerCheckReport>()
        val ssl = CompletableDeferred<ServerCheckReport>()
        val calls = mutableListOf<ServerCheckKind>()
        val controller = ServerChecksController(this, { kind -> calls += kind; if (kind == ServerCheckKind.ONLINE) online.await() else ssl.await() })
        controller.run(); controller.run(); runCurrent()
        assertEquals(listOf(ServerCheckKind.ONLINE), calls)
        controller.select(ServerCheckKind.SSL); controller.run(); controller.run(); runCurrent()
        assertEquals(listOf(ServerCheckKind.ONLINE, ServerCheckKind.SSL), calls)
        ssl.complete(report(ServerCheckKind.SSL, listOf(ServerCheckRow("ssl", status = "skip")))); runCurrent()
        online.complete(report(ServerCheckKind.ONLINE, listOf(ServerCheckRow("ping", online = false)))); runCurrent()
        assertEquals("ssl", controller.state.value.current.report!!.servers.single().id)
        controller.select(ServerCheckKind.ONLINE)
        assertEquals("ping", controller.state.value.current.report!!.servers.single().id)
        assertFalse(controller.state.value.current.running)
    }

    @Test fun failedRepeatKeepsPreviousResultsAndTimestampThenAllowsAnotherRun() = runTest {
        var calls = 0
        var time = 100L
        val controller = ServerChecksController(this, { kind ->
            if (++calls == 2) error("Проверка недоступна")
            report(kind, listOf(ServerCheckRow("server$calls", online = true)))
        }, { time })
        controller.run(); runCurrent()
        time = 200
        controller.run(); runCurrent()
        assertEquals("server1", controller.state.value.current.report!!.servers.single().id)
        assertEquals(100L, controller.state.value.current.completedAt)
        assertNotNull(controller.state.value.current.error)
        assertFalse(controller.state.value.current.running)
        controller.run(); runCurrent()
        assertEquals("server3", controller.state.value.current.report!!.servers.single().id)
        assertEquals(200L, controller.state.value.current.completedAt)
        assertNull(controller.state.value.current.error)
    }

    @Test fun cancellationDoesNotPublishAnErrorOrLeaveTheCheckRunning() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = ServerChecksController(owner, { awaitCancellation() })
        controller.run(); runCurrent()
        assertTrue(controller.state.value.current.running)
        owner.cancel(); runCurrent()
        assertFalse(controller.state.value.current.running)
        assertNull(controller.state.value.current.error)
    }
    @Test fun pullingLoadsSavedResultsWithoutRunningEitherCheck() = runTest {
        val checks = mutableListOf<ServerCheckKind>(); val reads = mutableListOf<ServerCheckKind>()
        val controller = ServerChecksController(this, { kind -> checks += kind; report(kind) }, load = { kind ->
            reads += kind; report(kind, listOf(ServerCheckRow("saved", online = true))).copy(completedAt = 123L)
        })
        controller.refresh(); runCurrent(); controller.select(ServerCheckKind.SSL); controller.refresh(); runCurrent()
        assertTrue(checks.isEmpty()); assertEquals(ServerCheckKind.entries, reads)
        assertEquals(123L, controller.state.value.current.completedAt)
        controller.run(); runCurrent(); assertEquals(listOf(ServerCheckKind.SSL), checks)
    }
    @Test fun refreshFailureKeepsPreviousResultsAndCannotRaceAManualRun() = runTest {
        val finish = CompletableDeferred<ServerCheckReport>(); var checkCalls = 0; var readCalls = 0
        val controller = ServerChecksController(this, { kind -> checkCalls++; report(kind) }, load = { _ ->
            if (++readCalls == 2) error("Нет соединения") else finish.await()
        })
        controller.refresh(); controller.run(); runCurrent(); assertEquals(0, checkCalls)
        finish.complete(report(ServerCheckKind.ONLINE, listOf(ServerCheckRow("saved"))).copy(completedAt = 42L)); runCurrent()
        controller.refresh(); runCurrent()
        assertEquals("saved", controller.state.value.current.report!!.servers.single().id)
        assertEquals(42L, controller.state.value.current.completedAt); assertFalse(controller.state.value.current.refreshing)
        assertEquals("Нет соединения", controller.state.value.current.error)
    }

}
