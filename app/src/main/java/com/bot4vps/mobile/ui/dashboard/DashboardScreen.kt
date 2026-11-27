package com.bot4vps.mobile.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.notifications.PhoneNotificationService
import com.bot4vps.mobile.ui.components.*
import com.bot4vps.mobile.ui.control.EventDetailsDialog
import com.bot4vps.mobile.ui.settings.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.*
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onBack: (() -> Unit)? = null, onServer: (String) -> Unit, onServers: () -> Unit, onAddServer: () -> Unit,
    onMonitor: () -> Unit, onGateway: (String) -> Unit = {}, onGateways: () -> Unit = {}, onEvents: () -> Unit, onLogin: () -> Unit, viewModel: DashboardViewModel = hiltViewModel(), updates: UpdatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gateways by viewModel.gateways.inventory.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var selectedEvent by remember { mutableStateOf<JsonObject?>(null) }
    selectedEvent?.let { EventDetailsDialog(it, onDismiss = { selectedEvent = null }) }
    LaunchedEffect(state.update) { updates.acceptSnapshot(state.update) }
    LaunchedEffect(lifecycle, viewModel) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.observe() } }
    UpdateDialogs(updates)
    LaunchedEffect(lifecycle, updates) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { updates.observe() } }
    PanelScaffold(topBar = { onBack?.let { back ->
        IconButton(onClick = back, modifier = Modifier.statusBarsPadding()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
    } }) { padding ->
        Refreshable(state.loading, { viewModel.refresh(); updates.refresh() }, Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = refreshArrangement(14.dp)) {
            item(key = "greeting") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (LocalTime.now().hour) { in 5..11 -> "Доброе утро!"; in 12..17 -> "Добрый день!"; in 18..22 -> "Добрый вечер!"; else -> "Доброй ночи!" }, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    com.bot4vps.mobile.ui.security.LoginQrButton()
                    com.bot4vps.mobile.ui.components.SecurityAlertButton()
                    }
                    Text("Вот что происходит с вашей инфраструктурой", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "summary") { PanelCard("Серверы") {
                Text("${state.online} в сети · ${state.offline} офлайн · ${state.servers.size} всего", style = MaterialTheme.typography.bodyMedium)
                state.serverError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (state.servers.isEmpty()) Text(if (state.loading) "Загрузка…" else "Нет серверов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.widgetServers.forEach { server -> DashboardServerCard(server, state.metrics[server.id], state.pings[server.id], state.serverSamples[server.id].orEmpty()) { onServer(server.id) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onServers, modifier = Modifier.weight(1f)) { Text("Все серверы →") }
                    TextButton(onClick = onAddServer, modifier = Modifier.weight(1f)) { Text("Добавить сервер") }
                }
            } }
            if (gateways.enabled) item(key = "gateways") { com.bot4vps.mobile.ui.gateways.DashboardGateways(gateways, onGateway, onGateways) }
            item(key = "system") { PanelCard("Состояние системы") {
                DashboardSystem(state.system.takeIf { state.systemError == null }, state.samples)
                state.systemError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onMonitor, modifier = Modifier.fillMaxWidth()) { Text("Подробная информация →") }

            } }
            item(key = "events") { PanelCard("Журнал уведомлений") {
                state.eventsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.events.isEmpty()) Text(if (state.loading) "Загрузка событий…" else "Событий пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.events.forEach { event -> Row(Modifier.fillMaxWidth().clickable {
                    selectedEvent = event
                    viewModel.markEventRead(event) { id -> PhoneNotificationService.dismissEvents(context, setOf(id)) }
                }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(event.text("message") ?: event.text("title") ?: "Событие", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(displayTime(event.text("timestamp")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                TextButton(onClick = onEvents) { Text("Все события →") }
            } }
            item(key = "updates") { PanelButton(onClick = updates::checkFromDashboard, enabled = !updates.busy && !updates.active, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (updates.busy) "Проверяем обновления…" else updates.available?.let { "Обновления Bot4VPS · $it" } ?: "Обновления Bot4VPS")
            } }
            updates.error?.let { item { ErrorCard(it, updates::checkFromDashboard) } }
            if (updates.progress != null) item { UpdateProgress(updates, onLogin) }
        }
    }
    }
}

@Composable
private fun DashboardServerCard(server: ServerSummary, metrics: ServerMetrics?, ping: ServerPing?, samples: List<SystemSample>, onClick: () -> Unit) {
    val online = server.online == true
    PanelSurfaceCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(server.name ?: server.id, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val latency = ping?.latencyMs
                val unavailable = ping != null && !ping.ok && ping.ms == null
                StatusBadge(when { ping == null -> "Пинг: …"; unavailable -> "Пинг: —"; latency != null -> "${latency.toInt()} мс"; else -> "Нет ответа" },
                    when { ping == null -> StatusTone.NEUTRAL; unavailable -> StatusTone.WARNING; latency == null -> StatusTone.BAD; latency <= 300 -> StatusTone.GOOD; latency <= 500 -> StatusTone.WARNING; else -> StatusTone.BAD }, maxLines = 1)
                StatusBadge(if (!online) "Офлайн" else if (!server.sshError.isNullOrBlank() || metrics?.ok == false) "SSH недоступен" else "В сети", if (!online) StatusTone.BAD else if (!server.sshError.isNullOrBlank() || metrics?.ok == false) StatusTone.WARNING else StatusTone.GOOD, maxLines = 1)
            }
            LoadChart(metrics?.cpu, metrics?.ramPct, samples)
            Text(server.hostIp ?: server.host ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metrics?.uptime ?: metrics?.error ?: "Загрузка показателей…", style = MaterialTheme.typography.labelSmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
