package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServiceArchivesTest {
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    @Test fun settingsUsePanelValuesRatherThanPhoneDefaults() {
        assertEquals(ServiceReserveSettings(true, 2), decodeServiceReserve(obj("""{"settings":{"enabled":true,"keep":2}}""")))
        assertEquals(ServiceReserveSettings(false, 3), decodeServiceReserve(obj("""{"settings":{"enabled":false,"keep":3}}""")))
    }
    @Test fun failedOrMissingSettingsAreNeverDecodedAsDisabled() {
        for (raw in listOf("{}", """{"settings":null}""", """{"settings":{"keep":3}}""", """{"settings":{"enabled":true}}""")) assertTrue(runCatching { decodeServiceReserve(obj(raw)) }.isFailure)
    }
    @Test fun changingAutoReserveKeepsLatestPanelRetention() {
        val result = serviceReserveRequest(ServiceReserveSettings(false, 2), enabled = true)
        assertTrue(result.flag("enabled")); assertEquals(2, result.number("keep")?.toInt())
    }
    @Test fun changingRetentionKeepsLatestPanelAutoReserve() {
        val result = serviceReserveRequest(ServiceReserveSettings(true, 3), keep = 2)
        assertTrue(result.flag("enabled")); assertEquals(2, result.number("keep")?.toInt())
    }
    @Test fun invalidRetentionDoesNotReachThePanel() {
        assertTrue(runCatching { serviceReserveRequest(ServiceReserveSettings(true, 3), keep = 99) }.isFailure)
    }
    @Test fun vpsAndDifferentOpenwrtGenerationsRemainSeparate() {
        val groups = serviceArchiveGroups(listOf(obj("""{"service":"3x-ui","name":"3x-ui","version":"v2.8.0"}"""), obj("""{"service":"podkop","name":"Podkop","openwrt_major":"23","size":10}"""), obj("""{"service":"podkop","name":"Podkop","openwrt_major":"24","size":20}""")))
        assertEquals(3, groups.size); assertTrue(groups.first { it.service == "3x-ui" }.removable)
        assertEquals(setOf("23", "24"), groups.filter { it.service == "podkop" }.map { it.major }.toSet())
        assertTrue(groups.filter { it.service == "podkop" }.all { it.removable })
    }
    @Test fun aBusyEntryBlocksDeletingItsWholeGroup() {
        val group = serviceArchiveGroups(listOf(obj("""{"service":"podkop","openwrt_major":"24","busy":false,"size":10}"""), obj("""{"service":"podkop","openwrt_major":"24","busy":true,"size":20}"""))).single()
        assertTrue(group.busy); assertFalse(group.removable); assertEquals(30.0, group.bytes, .001)
    }
    @Test fun unknownMajorCannotOfferGroupDeletion() {
        assertFalse(serviceArchiveGroups(listOf(obj("""{"service":"podkop","openwrt_major":"unknown"}"""))).single().removable)
    }
    @Test fun serviceArchiveWorkflowHasNoGenericDownloadOrUploadActions() {
        val view = PanelWorkflows.views("files").first { it.id == "service_archives" }
        assertEquals("GET /api/files/services", view.operation)
        assertTrue(PanelWorkflows.actions("files", view.id).isEmpty())
    }
}
