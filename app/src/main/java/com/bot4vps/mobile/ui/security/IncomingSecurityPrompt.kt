package com.bot4vps.mobile.ui.security

import com.bot4vps.mobile.ui.components.PanelAlertDialog

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.SecurityRepository
import com.bot4vps.mobile.data.repository.SecuritySnapshot
import com.bot4vps.mobile.data.repository.PanelTotpRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IncomingSecurityViewModel @Inject constructor(val repository: SecurityRepository, val prefs: PreferencesManager,
    private val registration: PanelTotpRepository) : ViewModel() {
    var snoozed by mutableStateOf(mapOf<String, Long>()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun later(origin: String, id: String) { snoozed = snoozed + (securityPromptKey(origin, id) to System.nanoTime() + 60_000_000_000L) }
    fun reveal(origin: String?, id: String) { snoozed = snoozed.filterKeys { it != securityPromptKey(origin.orEmpty(), id) && !(origin == null && it.substringAfter('\n') == id) } }
    fun revealAll() { snoozed = emptyMap() }
    fun dismissError() { error = null }
    fun decide(origin: String, request: SecurityRequest, action: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                if (request.informational) repository.acknowledge(request.id, origin) else repository.decide(request.id, action, origin)
                if (request.purpose in setOf("two_factor_enable", "primary_transfer") && action == "approve") registration.enable(origin, request.id, request.username)
                later(origin, request.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = "$origin\n" + if (e is java.io.IOException && e !is ApiError && e !is MobileAccessRevoked)
                    "Ответ панели не получен. Проверьте результат в браузере после восстановления связи." else errorText(e)
                try { repository.refreshFor(origin) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            } finally { busy = false }
        }
    }
}

@Composable
fun SecurityAlertsHost(notificationId: String?, notificationOrigin: String?, onNotificationConsumed: () -> Unit, content: @Composable () -> Unit) {
    val viewModel: IncomingSecurityViewModel = hiltViewModel()
    val inboxes by viewModel.repository.inboxes.collectAsState()
    val profiles by viewModel.prefs.profiles.collectAsState(initial = emptyList())
    val count = inboxes.values.filter { snapshot -> profiles.any { it.url == snapshot.origin && !it.revoked && !it.localDisconnected } }
        .sumOf { snapshot -> visibleSecurityRequests(snapshot.requests, snapshot.now()).size }
    CompositionLocalProvider(com.bot4vps.mobile.ui.components.LocalSecurityAlerts provides com.bot4vps.mobile.ui.components.SecurityAlerts(count, viewModel::revealAll)) {
        content()
        IncomingSecurityPrompt(notificationId, notificationOrigin, onNotificationConsumed, viewModel)
    }
}

/** All paired services; approving always uses the displayed origin, even after changing tabs. */
@Composable
fun IncomingSecurityPrompt(notificationId: String? = null, notificationOrigin: String? = null, onNotificationConsumed: () -> Unit = {}, viewModel: IncomingSecurityViewModel = hiltViewModel()) {
    val inboxes by viewModel.repository.inboxes.collectAsState()
    val profiles by viewModel.prefs.profiles.collectAsState(initial = emptyList())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { tick = System.nanoTime(); delay(1000) } } }
    LaunchedEffect(notificationId, notificationOrigin) { notificationId?.let { viewModel.reveal(notificationOrigin, it) } }
    val candidates = inboxes.values.filter { it.supported == true && profiles.any { profile -> profile.url == it.origin && !profile.revoked && !profile.localDisconnected } }
        .flatMap { snapshot -> visibleSecurityRequests(snapshot.requests, snapshot.now()).map { snapshot to it } }
        .filter { (snapshot, request) -> (viewModel.snoozed[securityPromptKey(snapshot.origin, request.id)] ?: 0) <= tick }
        .sortedWith(compareBy<Pair<SecuritySnapshot, SecurityRequest>> { if (it.second.id == notificationId && (notificationOrigin == null || it.first.origin == notificationOrigin)) 0 else if (!it.second.informational) 1 else 2 }.thenBy { it.second.started ?: 0.0 })
    val pending = candidates.firstOrNull()
    LaunchedEffect(pending?.first?.origin, pending?.second?.id, notificationId) {
        if (notificationId != null && pending != null && pending.second.id == notificationId && (notificationOrigin == null || pending.first.origin == notificationOrigin)) onNotificationConsumed()
    }
    if (viewModel.error == null) pending?.let { (snapshot, incoming) ->
        PanelAlertDialog(onDismissRequest = { if (!viewModel.busy) viewModel.later(snapshot.origin, incoming.id) },
            title = { Text(securityTitle(incoming)) },
            text = { Column {
                profiles.firstOrNull { it.url == snapshot.origin }?.name?.takeIf { it != snapshot.origin }?.let { Text("Сервис: $it") }
                Text("Адрес: ${snapshot.origin}")
                if (incoming.purpose == "login") Text("Запрошен вход в веб-панель. Подтвердите, если это ваш вход.")
                if (incoming.purpose == "unlink") Text("После подтверждения доступ этого телефона к сервису будет отозван. Коды Authenticator сохранятся.")
                if (incoming.purpose == "primary_transfer") Text("После подтверждения этот телефон станет основным. Код 2FA панели будет перенесён сюда и удалён с прежнего основного телефона. До завершения переноса прежняя защита действует.")
                if (incoming.purpose == "unlink_other") Text("Будет отозван доступ телефона «${incoming.targetName.ifBlank { incoming.targetDeviceId }}». Доступ этого телефона сохранится.")
                if (incoming.purpose == "unlink") Text(primaryUnlinkWarning(snapshot.isPrimary, snapshot.deviceCount))
                if (incoming.message.isNotBlank()) Text(incoming.message)
                if (incoming.purpose == "two_factor_enable") Text("Приложение сохранит код этого сервиса и включит 2FA без сканирования QR-кода. Подтвердите, если вы запросили это действие в веб-панели.")
                if (incoming.purpose == "two_factor_disable") Text("2FA будет отключена. Код этого сервиса будет удалён из Authenticator. Другие коды и привязка телефона сохранятся.")
                if (incoming.event == "two_factor_disabled") Text("Двухфакторная аутентификация отключена на этом сервисе. Связанный код удалён из Authenticator, остальные коды сохранены.")
                snapshot.error?.let { Text("Нет связи с сервисом: $it", color = MaterialTheme.colorScheme.error) }
                SecurityRequestDetails(incoming, snapshot.now())
            } },
            confirmButton = {
                if (incoming.informational) TextButton(enabled = !viewModel.busy, onClick = { viewModel.decide(snapshot.origin, incoming, "ack") }) { Text("Ок") }
                else if ("approve" in securityActions(incoming, snapshot.now())) TextButton(enabled = !viewModel.busy, onClick = { viewModel.decide(snapshot.origin, incoming, "approve") }) {
                    Text(if (viewModel.busy) "Отправляем…" else when (incoming.purpose) { "login" -> "Подтвердить вход"; "unlink", "unlink_other" -> "Отвязать телефон"; "primary_transfer" -> "Принять роль"; "two_factor_enable" -> "Включить 2FA"; "two_factor_disable" -> "Отключить 2FA"; else -> "Выключить защиту" })
                }
            },
            dismissButton = {
                securityActions(incoming, snapshot.now()).filter { it != "approve" }.forEach { action ->
                    TextButton(enabled = !viewModel.busy, onClick = { viewModel.decide(snapshot.origin, incoming, action) }) { Text(if (action == "cancel") "Отменить отвязку" else "Отклонить") }
                }
            })
    }
    viewModel.error?.let { PanelAlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Подтверждение действия") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("Ок") } }) }
}
