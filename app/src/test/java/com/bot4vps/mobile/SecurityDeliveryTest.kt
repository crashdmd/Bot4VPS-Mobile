package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityDeliveryTest {
    private val origin = "https://panel.example:8444"
    private fun profile(url: String = origin) = PanelProfile(url = url, name = url, deviceId = url)
    private fun request(id: String = "transfer") = SecurityRequest(id, purpose = "primary_transfer", nonce = "nonce-$id")
    private fun snapshot(received: Set<String> = emptySet(), supported: Boolean = true) = SecuritySnapshot(
        origin = origin, supported = true, requests = listOf(request()), receiptSupported = supported, receivedRequestIds = received)

    @Test fun slowReceiptPublishesTheRequestFirstAndCannotBlockPolling() = runTest {
        var fetches = 0
        val inbox = PairedSecurityInbox(MutableStateFlow(listOf(profile())), afterDelivery = { awaitCancellation() }) {
            fetches++; snapshot()
        }
        val worker = backgroundScope.launch { inbox.poll(5000) }
        runCurrent()
        assertEquals("transfer", inbox.state.value.getValue(origin).requests.single().id)
        assertNull(inbox.state.value.getValue(origin).error)
        advanceTimeBy(8001); runCurrent()
        assertEquals(2, fetches)
        worker.cancel(); runCurrent(); assertTrue(worker.isCompleted)
    }

    @Test fun lostReceiptResponseKeepsTheRequestAndStopsRepeatingAfterServerConfirmation() = runTest {
        var accepted = false
        var posts = 0
        val inbox = PairedSecurityInbox(MutableStateFlow(listOf(profile())), afterDelivery = { state ->
            acknowledgePrimaryRequests(state) { id, nonce ->
                assertEquals("transfer", id); assertEquals("nonce-transfer", nonce)
                posts++; accepted = true; throw IOException("Response lost after server accepted receipt")
            }
        }) { snapshot(if (accepted) setOf("transfer") else emptySet()) }
        val worker = backgroundScope.launch { inbox.poll(5000) }
        runCurrent(); assertEquals(1, posts)
        assertEquals("transfer", inbox.state.value.getValue(origin).requests.single().id)
        assertNull(inbox.state.value.getValue(origin).error)
        advanceTimeBy(5001); runCurrent(); assertEquals(1, posts)
        assertEquals("transfer", inbox.state.value.getValue(origin).requests.single().id)
        worker.cancel()
    }

    @Test fun failedReceiptIsRetriedWithoutApprovingOrRemovingTheRequest() = runTest {
        var posts = 0
        val inbox = PairedSecurityInbox(MutableStateFlow(listOf(profile())), afterDelivery = { state ->
            acknowledgePrimaryRequests(state) { _, _ -> if (++posts == 1) throw IOException("No connection") }
        }) { snapshot() }
        val worker = backgroundScope.launch { inbox.poll(5000) }
        runCurrent(); assertEquals(1, posts)
        advanceTimeBy(5001); runCurrent(); assertEquals(2, posts)
        assertEquals(listOf(request()), inbox.state.value.getValue(origin).requests)
        assertNull(inbox.state.value.getValue(origin).error)
        worker.cancel()
    }

    @Test fun oldPanelAndOtherRequestTypesNeverReceiveAReceiptPost() = runTest {
        var posts = 0
        acknowledgePrimaryRequests(snapshot(supported = false)) { _, _ -> posts++ }
        acknowledgePrimaryRequests(snapshot().copy(requests = listOf(
            SecurityRequest("login", purpose = "login", nonce = "login-nonce"),
            SecurityRequest("notice", event = "device_connected"),
            request().copy(nonce = ""), request("expired").copy(expires = 1.0)))) { _, _ -> posts++ }
        assertEquals(0, posts)
    }

    @Test fun securityRetryNeverWaitsMoreThanThirtySecondsAndAnotherPanelKeepsPolling() = runTest {
        val attempts = mutableListOf<Long>()
        var healthy = 0
        val second = "https://second.example"
        val inbox = PairedSecurityInbox(MutableStateFlow(listOf(profile(), profile(second)))) { url ->
            if (url == origin) {
                attempts += currentTime
                if (attempts.size <= 5) throw IOException("Temporary failure")
                snapshot()
            } else { healthy++; SecuritySnapshot(origin = second, supported = true) }
        }
        val worker = backgroundScope.launch { inbox.poll(5000) }
        runCurrent(); advanceTimeBy(150001); runCurrent()
        assertTrue(attempts.zipWithNext().all { (a,b) -> b-a <= 30_000 })
        assertTrue(healthy > attempts.size)
        assertEquals(listOf(request()), inbox.state.value.getValue(origin).requests)
        assertNull(inbox.state.value.getValue(origin).error)
        worker.cancel()
    }
}
