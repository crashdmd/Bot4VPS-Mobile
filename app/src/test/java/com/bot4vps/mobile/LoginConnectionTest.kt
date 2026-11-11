package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.MeResponse
import com.bot4vps.mobile.ui.auth.LoginViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoginConnectionTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun silentPanelStopsAfterTenSecondsAndRetryKeepsTheAddress() = runTest {
        var attempts = 0
        val viewModel = LoginViewModel({
            attempts++
            if (attempts == 1) awaitCancellation()
            MeResponse(mobileAuthenticated = true)
        }, {}, MutableStateFlow("https://192.168.1.5:8444"))
        runCurrent()
        advanceTimeBy(9999); runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceTimeBy(1); runCurrent()
        val failed = viewModel.uiState.value
        assertFalse(failed.isLoading)
        assertFalse(failed.pairingRequired)
        assertTrue(failed.error!!.contains("10 секунд"))
        assertTrue(failed.error!!.contains("той же сети"))
        assertEquals("https://192.168.1.5:8444", failed.address)
        viewModel.reconnect(); runCurrent()
        assertTrue(viewModel.uiState.value.loggedIn)
        assertNull(viewModel.uiState.value.error)
        assertEquals(2, attempts)
    }

    @Test fun changingAddressCancelsImmediatelyAndOldSuccessCannotOpenThePanel() = runTest {
        var attempts = 0
        val address = MutableStateFlow<String?>("https://192.168.1.5:8444")
        val viewModel = LoginViewModel({
            val current = ++attempts
            if (current == 1) withContext(NonCancellable) { delay(5000) }
            MeResponse(mobileAuthenticated = current == 1)
        }, {}, address)
        runCurrent()
        viewModel.cancelConnection()
        assertFalse(viewModel.uiState.value.isLoading)
        address.value = "https://other.example:8444"
        viewModel.reconnect(); runCurrent()
        assertFalse(viewModel.uiState.value.loggedIn)
        advanceTimeBy(5000); runCurrent()
        assertFalse(viewModel.uiState.value.loggedIn)
        assertEquals("https://other.example:8444", viewModel.uiState.value.address)
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun duplicateReconnectDoesNotSendAnotherAttempt() = runTest {
        var attempts = 0
        val viewModel = LoginViewModel({ attempts++; delay(1000); MeResponse(mobileAuthenticated = true) }, {}, MutableStateFlow(null))
        repeat(4) { viewModel.reconnect() }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(1, attempts)
        assertTrue(viewModel.uiState.value.loggedIn)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun pairingAndSessionShareOneDeadlineAndSuccessfulPairingIsRetained() = runTest {
        var checks = 0
        var pairs = 0
        val viewModel = LoginViewModel({
            checks++
            if (checks == 1) throw MobilePairingRequired("Нужна привязка")
            delay(6000)
            MeResponse(mobileAuthenticated = true)
        }, { pairs++; delay(6000) }, MutableStateFlow("https://panel.example:8444"))
        runCurrent()
        viewModel.onPairingCode("fixture"); viewModel.pairPhone(); runCurrent()
        advanceTimeBy(10000); runCurrent()
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.pairingRequired)
        assertTrue(viewModel.uiState.value.pairingSuccess)
        assertTrue(viewModel.uiState.value.error!!.contains("10 секунд"))
        assertEquals(1, pairs)
        viewModel.reconnect(); runCurrent(); advanceTimeBy(6000); runCurrent()
        assertTrue(viewModel.uiState.value.loggedIn)
        assertEquals(1, pairs)
    }

    @Test fun unavailableNetworkDoesNotRequestANewPairing() = runTest {
        val viewModel = LoginViewModel({ throw java.net.UnknownHostException("internal resolver details") }, {}, MutableStateFlow(null))
        runCurrent()
        assertFalse(viewModel.uiState.value.pairingRequired)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.error!!.contains("адрес"))
        assertFalse(viewModel.uiState.value.error!!.contains("internal resolver"))
    }

    @Test fun callerCancellationIsNotReportedAsAConnectionTimeout() = runTest {
        val error = runCatching { withTimeout(1000) { connectToPanel { awaitCancellation() } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertNull(connectToPanel<String?> { null })
    }
}
