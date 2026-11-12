package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.services.ServicesState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerChecksPresentationTest {
    private fun report(kind: ServerCheckKind, rows: List<ServerCheckRow>) =
        ServerCheckReport(ok = true, kind = kind.id, servers = rows, completedAt = 42)

    @Test fun savedResultsStayHiddenUntilRequestedAndSwitchingTabsHidesThemAgain() = runTest {
        val controller = ServerChecksController(this, { kind -> report(kind, listOf(ServerCheckRow("new", online = true, ms = 1.0))) },
            load = { kind -> report(kind, listOf(ServerCheckRow("saved", online = true, ms = 5.0))) })
        controller.refresh(); runCurrent()
        assertTrue(controller.state.value.hasResults)
        assertEquals(42L, controller.state.value.current.completedAt)
        assertTrue(controller.state.value.visibleResults.isEmpty())
        controller.showResults()
        assertEquals("saved", controller.state.value.visibleResults.single().id)
        controller.select(ServerCheckKind.SSL); controller.select(ServerCheckKind.ONLINE)
        assertTrue(controller.state.value.visibleResults.isEmpty())
        controller.run(); runCurrent()
        assertEquals("new", controller.state.value.visibleResults.single().id)
        controller.hideResults()
        assertTrue(controller.state.value.visibleResults.isEmpty())
        assertTrue(controller.state.value.hasResults)
    }

    @Test fun failedNewCheckKeepsOldReportHiddenAndAvailableViaTheExplicitButton() = runTest {
        var fail = false
        val controller = ServerChecksController(this, { kind ->
            if (fail) error("Нет соединения")
            report(kind, listOf(ServerCheckRow("old", online = true, ms = 1.0)))
        })
        controller.run(); runCurrent(); fail = true
        controller.run(); runCurrent()
        assertTrue(controller.state.value.visibleResults.isEmpty())
        assertEquals(42L, controller.state.value.current.completedAt)
        assertEquals("Нет соединения", controller.state.value.current.error)
        controller.showResults()
        assertEquals("old", controller.state.value.visibleResults.single().id)
    }

    @Test fun emptyAndUnconfiguredResultsNeverEnableTheButtonOrSorting() = runTest {
        val controller = ServerChecksController(this, { kind -> report(kind, listOf(ServerCheckRow("skip", status = "skip"))) })
        controller.select(ServerCheckKind.SSL); controller.run(); runCurrent()
        assertFalse(controller.state.value.hasResults)
        assertTrue(controller.state.value.visibleResults.isEmpty())
        controller.sort()
        assertTrue(controller.state.value.current.sortAscending)
    }

    @Test fun sortingPingAndCertificatesUsesNumbersAndKeepsMissingValuesLastInBothDirections() {
        val ping = report(ServerCheckKind.ONLINE, listOf(ServerCheckRow("high", online = true, ms = 120.0),
            ServerCheckRow("timeout", online = false, ms = 2000.0), ServerCheckRow("zero", online = true, ms = 0.0),
            ServerCheckRow("low", online = true, ms = 9.0)))
        assertEquals(listOf("zero", "low", "high", "timeout"), ping.sortedServers(ServerCheckKind.ONLINE, true).map { it.id })
        assertEquals(listOf("high", "low", "zero", "timeout"), ping.sortedServers(ServerCheckKind.ONLINE, false).map { it.id })
        val ssl = report(ServerCheckKind.SSL, listOf(ServerCheckRow("valid", status = "valid", daysLeft = 120),
            ServerCheckRow("error", status = "error", daysLeft = 1000), ServerCheckRow("expired", status = "expired", daysLeft = -3),
            ServerCheckRow("warning", status = "warning", daysLeft = 9), ServerCheckRow("skip", status = "skip")))
        assertEquals(listOf("expired", "warning", "valid", "error"), ssl.sortedServers(ServerCheckKind.SSL, true).map { it.id })
        assertEquals(listOf("valid", "warning", "expired", "error"), ssl.sortedServers(ServerCheckKind.SSL, false).map { it.id })
        assertEquals(listOf("Действуют: 1 · Скоро истекают: 1", "Истекли: 1 · Ошибки: 1"), ssl.summaryLines(ServerCheckKind.SSL))
    }

    @Test fun sortingTogglesOnlyWhileThereIsAVisibleListAndSummaryCountsFailuresOnce() = runTest {
        val rows = listOf(ServerCheckRow("online", online = true, ms = 10.0),
            ServerCheckRow("offline", online = false, error = "timeout"), ServerCheckRow("error", error = "Connection failed"),
            ServerCheckRow("unknown"))
        val controller = ServerChecksController(this, { kind -> report(kind, rows) })
        controller.run(); runCurrent()
        controller.sort(); assertFalse(controller.state.value.current.sortAscending)
        controller.sort(); assertTrue(controller.state.value.current.sortAscending)
        assertEquals(listOf("В сети: 1 · Офлайн: 1", "Неизвестно: 1 · Ошибки: 1"), controller.state.value.current.report!!.summaryLines(ServerCheckKind.ONLINE))
        controller.hideResults(); controller.sort()
        assertTrue(controller.state.value.current.sortAscending)
    }

    @Test fun servicesStillFilterInstallationAcrossAllGroups() {
        val installed = ServiceServer("a", "A", "1", group = "Home", status = buildJsonObject { put("installed", true) })
        val absent = ServiceServer("b", "B", "2", group = "Work")
        val state = ServicesState(rows = listOf(installed, absent))
        assertEquals(listOf(installed, absent), state.filtered)
        assertEquals(listOf(installed), state.copy(filter = InstallationFilter.INSTALLED).filtered)
        assertEquals(listOf(absent), state.copy(filter = InstallationFilter.ABSENT).filtered)
        assertEquals(listOf(absent), state.copy(search = "Work").filtered)
    }
}
