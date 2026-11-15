package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairedSecurityInboxTest {
    private fun profile(origin: String) = PanelProfile(url = origin, name = origin, deviceId = origin)
    @Test fun unavailablePanelDoesNotBlockOtherPanelsWithIdenticalRequestIds() = runTest {
        val profiles = MutableStateFlow(listOf(profile("https://a"), profile("https://b"), profile("https://slow")))
        val inbox = PairedSecurityInbox(profiles) { origin ->
            if (origin.endsWith("slow")) awaitCancellation()
            SecuritySnapshot(origin = origin, supported = true, requests = listOf(SecurityRequest(id = "same", purpose = "login", nonce = origin)))
        }
        val worker = backgroundScope.launch { inbox.poll(5000) }
        runCurrent()
        assertEquals("https://a", inbox.state.value.getValue("https://a").requests.single().nonce)
        assertEquals("https://b", inbox.state.value.getValue("https://b").requests.single().nonce)
        assertNotEquals(securityPromptKey("https://a", "same"), securityPromptKey("https://b", "same"))
        worker.cancel()
    }
    @Test fun revokedProfilesAreRemovedAndLateRefreshCannotRestoreThem() = runTest {
        val p = profile("https://a")
        val profiles = MutableStateFlow(listOf(p))
        val inbox = PairedSecurityInbox(profiles) { origin -> SecuritySnapshot(origin = origin, supported = true) }
        val worker = backgroundScope.launch { inbox.poll() }
        runCurrent(); assertTrue(inbox.state.value.containsKey(p.url))
        profiles.value = listOf(p.copy(revoked = true))
        runCurrent(); assertTrue(inbox.state.value.isEmpty())
        inbox.refresh(p.url); assertTrue(inbox.state.value.isEmpty())
        worker.cancel()
    }
    @Test fun foregroundAndBackgroundSharePollingAndHandOffOnCancellation() = runTest {
        var count = 0
        val profiles = MutableStateFlow(listOf(profile("https://a")))
        val inbox = PairedSecurityInbox(profiles) { origin -> count++; SecuritySnapshot(origin = origin) }
        val foreground = backgroundScope.launch { inbox.poll(5000) }
        val background = backgroundScope.launch { inbox.poll(5000) }
        runCurrent(); assertEquals(1, count)
        advanceTimeBy(5000); runCurrent(); assertEquals(2, count)
        foreground.cancel(); runCurrent(); assertEquals(3, count)
        background.cancel()
    }
    @Test fun intermediateWebCodeStepsAreHiddenButValidatedCodeAndPhoneDecisionsAppear() {
        val rows = listOf(SecurityRequest("code", purpose = "unlink", state = "code", method = "totp"),
            SecurityRequest("waiting", purpose = "unlink", state = "waiting", method = "totp", deadline = 100.0),
            SecurityRequest("phone", purpose = "unlink", state = "phone"), SecurityRequest("notice", event = "unlink_started"),
            SecurityRequest("expired", purpose = "login", expires = 1.0))
        assertEquals(listOf("waiting", "phone"), visibleSecurityRequests(rows, 10.0).map { it.id })
    }
    @Test fun notificationSummaryShowsServerFailureAndNeverCopiesSecretsOrCommandOutput() {
        val event = buildJsonObject {
            put("type", "server"); put("reason", "server_offline"); put("title", "secret-title"); put("message", "password=secret-body")
            putJsonObject("details") { put("server_name", "Домашний сервер"); put("password", "hidden-pass"); put("secret", "hidden-totp"); put("output", "hidden-output") }
        }
        val preview = phoneEventNotification(event)
        assertEquals("Сервер недоступен", preview.first)
        assertTrue(preview.second.contains("Домашний сервер"))
        listOf("secret-title", "secret-body", "hidden-pass", "hidden-totp", "hidden-output").forEach { assertFalse(preview.toString().contains(it)) }
    }
}
