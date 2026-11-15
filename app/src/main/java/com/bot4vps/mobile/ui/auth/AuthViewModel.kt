package com.bot4vps.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.repository.AuthRepository
import com.bot4vps.mobile.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val baseUrl = authRepository.baseUrl
    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            try { authRepository.refreshProfiles() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { }
            val url = runCatching { authRepository.baseUrl.first() }.getOrNull()
            val paired = !url.isNullOrBlank() && runCatching { authRepository.isPairedApiAddress(url) }.getOrDefault(false)
            val revoked = runCatching { authRepository.profiles.first().any { com.bot4vps.mobile.data.model.panelProfileOrigin(it.url) == com.bot4vps.mobile.data.model.panelProfileOrigin(url) && it.revoked } }.getOrDefault(true)
            _startDestination.value = if (com.bot4vps.mobile.data.model.openAuthenticatorFirst(!url.isNullOrBlank(), paired, revoked)) Routes.ROOT_AUTHENTICATOR else Routes.LOGIN
        }
    }
}
