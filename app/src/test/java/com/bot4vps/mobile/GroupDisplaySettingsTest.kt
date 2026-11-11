package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.servers.ServersUiState
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GroupDisplaySettingsTest {
    @Test fun catalogIncludesEmptyGroups() {
        val state=ServersUiState(catalogGroups=listOf("Пустая","Рабочая"))
        assertEquals(listOf("Пустая","Рабочая"),state.groups)
        assertTrue(state.copy(selectedGroup="Пустая").filtered.isEmpty())
    }
    @Test fun visibilityUsesCatalogOrderAndIncludesNewGroups() {
        val settings=GroupDisplaySettings(setOf("А"))
        assertEquals(listOf("Б","В"),settings.visible(listOf("А","Б","В")))
    }
    @Test fun oldOrderIsIgnoredWithoutResettingVisibility() {
        val settings=decodeGroupDisplaySettings("""{"order":["В","Б","А"],"hidden":["А"]}""")
        assertEquals(setOf("А"),settings.hidden)
        assertEquals(listOf("Б","В"),settings.visible(listOf("А","Б","В")))
        assertFalse(Json.encodeToString(settings).contains("order"))
    }
    @Test fun renamePreservesHiddenStateAndOtherGroups() {
        val settings=GroupDisplaySettings(setOf("А","Б"))
        val renamed=settings.renamed("А","Г")
        assertEquals(setOf("Г","Б"),renamed.hidden)
        assertEquals(listOf("В"),renamed.visible(listOf("Г","Б","В")))
    }
    @Test fun savingTheSameNameDoesNotRevealAHiddenGroup() {
        val settings=GroupDisplaySettings(setOf("А","Б"))
        assertEquals(settings,settings.renamed("А","А"))
    }
    @Test fun visibilityCanBeChangedWithoutAffectingOtherGroups() {
        val settings=GroupDisplaySettings(setOf("А","Б"))
        val shown=settings.shown("А",true)
        assertEquals(setOf("Б"),shown.hidden)
        assertEquals(settings,shown.shown("А",false))
    }
    @Test fun deletingAGroupKeepsOtherVisibilityChoices() {
        assertEquals(setOf("Б"),GroupDisplaySettings(setOf("А","Б")).deleted("А").hidden)
    }
    @Test fun hidingAGroupKeepsItsServersInTheAllServersList() {
        val rows=listOf(ServerSummary("a",group="А"),ServerSummary("b",group="Б"))
        val state=ServersUiState(servers=rows,catalogGroups=listOf("А","Б"),groupDisplay=GroupDisplaySettings(setOf("А")))
        assertEquals(listOf("Б"),state.groups)
        assertEquals(rows,state.filtered)
    }
    @Test fun defaultAndOldSystemSettingBecomeDark() {
        assertEquals("dark",normalizePanelTheme(null))
        assertEquals("dark",normalizePanelTheme("system"))
        listOf("dark","light","glass").forEach { assertEquals(it,normalizePanelTheme(it)) }
    }
}
