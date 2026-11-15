package com.bot4vps.mobile.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.SecurityRepository
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(val repository: SecurityRepository, private val registration: com.bot4vps.mobile.data.repository.PanelTotpRepository) : ViewModel() {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    fun decide(request: SecurityRequest, action: String, onUnlinked: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val decided = repository.decide(request.id, action)
                if (decided.purpose in setOf("two_factor_enable", "primary_transfer") && action == "approve") {
                    registration.enable(repository.state.value.origin, decided.id, decided.username)
                }
                message = if (action == "approve") "Подтверждение принято" else "Запрос отменён"
                if (request.purpose == "unlink" && action == "approve") onUnlinked()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is java.io.IOException && e !is ApiError && e !is MobileAccessRevoked) "Результат подтверждения неизвестен: ответ панели не получен. Проверьте состояние в Web или после восстановления связи." else errorText(e); runCatching { repository.refresh() }.onFailure { if (it is CancellationException) throw it } }
            finally { busy = false }
        }
    }
    fun acknowledge(id: String) { viewModelScope.launch {
        try { repository.acknowledge(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
    } }
    fun dismissError() { error = null }
}

fun securityTime(seconds: Double?): String = seconds?.let {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it.toLong()))
} ?: "—"

@Composable
fun SecurityRequestDetails(request: SecurityRequest, serverNow: Double) {
    if (request.targetName.isNotBlank()) Text("Телефон: ${request.targetName}")
    if (request.username.isNotBlank()) Text("Пользователь: ${request.username}")
    if (request.agent.isNotBlank()) Text("Браузер: ${request.agent}")
    if (request.ip.isNotBlank()) Text("IP: ${request.ip}")
    if (request.started != null) Text("Время: ${securityTime(request.started)}", style = MaterialTheme.typography.bodySmall)
    if (request.purpose == "protection_disable") Text("После подтверждения телефон можно будет отвязать из Web без дополнительных подтверждений и ожидания.")
    if (request.purpose in setOf("unlink", "unlink_other") || request.event == "unlink_requested") {
        Text(when (request.method) { "delayed" -> "Отложенная отвязка на 72 часа"; "totp" -> "Подтверждение TOTP"; "telegram" -> "Подтверждение через Telegram"; else -> "Подтверждение на этом телефоне" })
        if (request.state == "code") Text("Ожидается ввод кода в Web-панели. Вы можете отменить процедуру.")
        request.deadline?.let { deadline ->
            val remaining = (deadline - serverNow).toLong().coerceAtLeast(0)
            Text(if (remaining == 0L && request.method == "delayed") "Срок ожидания прошёл. Выберите дальнейшее действие."
                else if (remaining == 0L) "Сверяем завершение отвязки с сервером…"
                else "${if (request.method == "delayed") "До возможности отвязать" else "Устройство будет отвязано через"}: ${remaining / 3600} ч ${(remaining % 3600) / 60} мин ${remaining % 60} с")
            Text("Срок: ${securityTime(deadline)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    request.expires?.let { Text("Действует до: ${securityTime(it)}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun SecurityScreen(onBack: () -> Unit, onUnlinked: () -> Unit, viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.repository.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.repository.poll() } }
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(1000); tick++ } } }
    val now = remember(state, tick) { state.now() }
    var confirm by remember { mutableStateOf<Pair<SecurityRequest, String>?>(null) }
    viewModel.error?.let { PanelAlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Не удалось выполнить действие") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("Ок") } }) }
    confirm?.let { (request, action) -> PanelAlertDialog(onDismissRequest = { confirm = null },
        title = { Text(securityTitle(request)) },
        text = { Column { Text("Подтвердите действие на сервисе ${state.origin}"); SecurityRequestDetails(request, now) } },
        confirmButton = { TextButton(onClick = { confirm = null; viewModel.decide(request, action, onUnlinked) }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Запросы безопасности") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(state.origin, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { item { ErrorCard(it) } }
            viewModel.message?.let { item { Text(it) } }
            if (state.supported == false) item { PanelCard { Text("Для запросов безопасности обновите панель до Bot4VPS 7.1.") } }
            if (state.supported == true && state.requests.isEmpty()) item { PanelCard { Text("Нет ожидающих запросов") } }
            items(state.requests, key = { it.id }) { request -> PanelCard(securityTitle(request)) {
                SecurityRequestDetails(request, now)
                if (request.informational) TextButton(onClick = { viewModel.acknowledge(request.id) }) { Text("Ок") }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    securityActions(request, now).forEach { action ->
                        TextButton(enabled = !viewModel.busy, onClick = {
                            if (action == "approve" && request.purpose != "login") confirm = request to action
                            else viewModel.decide(request, action, onUnlinked)
                        }) { Text(when(action) { "approve" -> if (request.purpose == "unlink") "Отвязать сейчас" else "Подтвердить"; "reject" -> "Отклонить"; else -> "Отменить отвязку" }) }
                    }
                }
            } }
        }
    }
}
