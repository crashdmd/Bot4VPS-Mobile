package com.bot4vps.mobile.ui.authenticator

import com.bot4vps.mobile.ui.components.PanelAlertDialog

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairingTotpViewModel @Inject constructor(val prefs: PreferencesManager, val security: SecurityRepository,
    private val registration: PanelTotpRepository) : ViewModel() {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun clearError() { error = null }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    fun later(origin: String) { viewModelScope.launch { prefs.dismissTotpOffer(origin) } }
    fun enable(origin: String) = action { registration.enable(origin); prefs.dismissTotpOffer(origin) }
    fun refresh() = action { security.refresh() }
}

/** Persisted after pairing, so navigation to the dashboard cannot swallow the offer. */
@Composable
fun PairingTotpPrompt(viewModel: PairingTotpViewModel = hiltViewModel()) {
    val offers by viewModel.prefs.pendingTotpOffers.collectAsState(initial = emptySet())
    val address by viewModel.prefs.baseUrl.collectAsState(initial = null)
    val user by viewModel.prefs.username.collectAsState(initial = null)
    val snapshot by viewModel.security.state.collectAsState()
    val origin = address?.let { runCatching { mobileOrigin(PanelUrl.endpoint(it, "/api/me")) }.getOrNull() }
    if (origin == null || origin !in offers || user == null) return
    LaunchedEffect(origin) { viewModel.refresh() }
    if (snapshot.origin == origin && (snapshot.twoFactorEnabled || snapshot.supported == true && !snapshot.totpSetupAllowed)) {
        LaunchedEffect(origin) { viewModel.later(origin) }
        return
    }
    if (viewModel.error != null) {
        PanelAlertDialog(onDismissRequest = viewModel::clearError, title = { Text("Включение 2FA") }, text = { Text(viewModel.error.orEmpty()) }, confirmButton = { TextButton(onClick = viewModel::clearError) { Text("Ок") } })
    } else if (snapshot.origin == origin && snapshot.supported != null) {
        PanelAlertDialog(onDismissRequest = { if (!viewModel.busy) viewModel.later(origin) }, title = { Text("Включить 2FA?") },
            text = { Text("$origin\n" + if (snapshot.supported == false) "Для включения 2FA через доверенный телефон обновите панель Bot4VPS. Сторонний аутентификатор можно настроить в веб-панели."
                else "Приложение сохранит код этого сервиса и включит двухфакторную аутентификацию. Коды будут доступны на телефоне без связи с панелью. Можно выбрать «Позже» и настроить сторонний аутентификатор в веб-панели.") },
            confirmButton = { if (snapshot.supported == true) TextButton(enabled = !viewModel.busy, onClick = { viewModel.enable(origin) }) { Text(if (viewModel.busy) "Включаем…" else "Включить") } },
            dismissButton = { TextButton(enabled = !viewModel.busy, onClick = { viewModel.later(origin) }) { Text("Позже") } })
    }
}
