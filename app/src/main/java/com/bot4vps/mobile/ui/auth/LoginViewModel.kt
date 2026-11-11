package com.bot4vps.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.repository.AuthRepository
import com.bot4vps.mobile.data.api.MobilePairingRequired
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.api.connectToPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject
import com.bot4vps.mobile.data.model.MeResponse

data class LoginUiState(
    val pairingRequired: Boolean = false,
    val pairingCode: String = "",
    val pairingSuccess: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val address: String? = null
)

@HiltViewModel
class LoginViewModel(
    private val checkSession: suspend () -> MeResponse,
    private val pairMobile: suspend (String) -> Unit,
    address: Flow<String?>,
) : ViewModel() {
    @Inject constructor(authRepository: AuthRepository) : this(
        authRepository::checkSession, authRepository::pairMobile, authRepository.baseUrl)
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()
    private var attempt: Job? = null
    private var generation = 0
    init {
        viewModelScope.launch { address.collect { value -> _uiState.update { it.copy(address = value) } } }
        reconnect()
    }

    private fun needsPairing(error: Exception) = error is MobilePairingRequired ||
        error is javax.net.ssl.SSLHandshakeException || error is HttpException && error.code() == 428 ||
        error is IllegalArgumentException

    fun reconnect() {
        if (_uiState.value.isLoading) return
        startAttempt { current ->
            val me = checkSession()
            if (generation == current) _uiState.update { it.copy(loggedIn = me.authenticated) }
        }
    }

    private fun startAttempt(action: suspend (Int) -> Unit) {
        val current = ++generation
        _uiState.update { it.copy(isLoading = true, loggedIn = false, error = null) }
        attempt = viewModelScope.launch {
            try {
                connectToPanel { action(current) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == current) _uiState.update { it.copy(pairingRequired = it.pairingRequired || needsPairing(e), error = errorText(e)) }
            } finally { if (generation == current) _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun cancelConnection() {
        generation++
        attempt?.cancel()
        _uiState.update { it.copy(isLoading = false, loggedIn = false) }
    }
    fun showPairing() { _uiState.update { it.copy(pairingRequired = true, error = null) } }
    fun onPairingCode(value: String) { _uiState.update { it.copy(pairingCode = value, error = null) } }
    fun onPairingError(value: String) { _uiState.update { it.copy(error = value) } }
    fun pairScannedCode(value: String) { onPairingCode(value); pairPhone() }
    fun pairPhone() {
        if (_uiState.value.isLoading) return
        val code = _uiState.value.pairingCode.trim()
        if (code.isBlank()) { onPairingError("Введите полный код привязки из Web-панели"); return }
        startAttempt { current ->
            pairMobile(code)
            if (generation != current) return@startAttempt
            _uiState.update { it.copy(pairingRequired = false, pairingCode = "", pairingSuccess = true) }
            val me = checkSession()
            if (generation == current) _uiState.update { it.copy(loggedIn = me.authenticated) }
        }
    }
    suspend fun pairFromWeb(code: String) = pairMobile(code)
}
