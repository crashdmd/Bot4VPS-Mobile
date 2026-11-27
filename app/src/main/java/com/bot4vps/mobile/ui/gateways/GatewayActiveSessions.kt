package com.bot4vps.mobile.ui.gateways

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.PanelCard
import com.bot4vps.mobile.ui.components.PanelSecondaryButton
import com.bot4vps.mobile.ui.components.PanelTextField
import kotlinx.coroutines.flow.collect

@Composable
internal fun rememberGatewaySessions(
    nodeId: String, enabled: Boolean, load: suspend () -> ActiveSessionSample,
): Pair<ActiveSessionUpdate?, List<SessionConnection>> {
    val history = remember(nodeId) { SessionHistory() }
    var update by remember(nodeId) { mutableStateOf<ActiveSessionUpdate?>(null) }
    var recorded by remember(nodeId) { mutableStateOf<List<SessionConnection>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestLoad by rememberUpdatedState(load)
    LaunchedEffect(lifecycle, nodeId, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            activeSessionUpdates { latestLoad() }.collect { result ->
                update = result
                val rows = (result as? ActiveSessionUpdate.Received)?.sample?.connections
                if (!rows.isNullOrEmpty()) recorded = history.record(rows)
            }
        }
    }
    return update to recorded
}

@Composable
internal fun GatewayActiveSessions(nodeId: String, update: ActiveSessionUpdate?, recorded: List<SessionConnection>) {
    var expanded by rememberSaveable(nodeId) { mutableStateOf(false) }
    var deviceFilter by rememberSaveable(nodeId) { mutableStateOf("") }
    var destinationFilter by rememberSaveable(nodeId) { mutableStateOf("") }
    var fakeIpFilter by rememberSaveable(nodeId) { mutableStateOf<Boolean?>(null) }
    if (update == ActiveSessionUpdate.Unsupported) return

    val sample = (update as? ActiveSessionUpdate.Received)?.sample
    PanelCard {
        Column(
            Modifier.fillMaxWidth().clickable(
                role = Role.Button, onClickLabel = if (expanded) "Скрыть соединения" else "Показать соединения",
            ) { expanded = !expanded },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Активные сеансы", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
            Text("Мбит/с · обновление каждые 3 с", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionSummary("WAN", sample?.wan, Modifier.weight(1f))
                SessionSummary("Устройства", sample?.total, Modifier.weight(1f))
            }
        }
        if (expanded) {
            HorizontalDivider()
            val rows = recorded.filterSessions(deviceFilter, destinationFilter, fakeIpFilter)
            val filtering = deviceFilter.isNotBlank() || destinationFilter.isNotBlank() || fakeIpFilter != null
            val message = when {
                update == ActiveSessionUpdate.Unavailable -> "Не удалось получить сеансы. Повторяем запрос."
                sample?.status == "ready" && sample.connections == null -> "Для списка соединений обновите панель."
                sample?.status == "ready" && rows.isNotEmpty() -> null
                sample?.status == "ready" && filtering -> "Совпадений нет."
                sample?.status == "ready" -> "Соединений пока нет."
                sample?.status == "integration" -> "Обновите интеграцию Bot4VPS во вкладке «Доступ»."
                sample?.status == "accounting" -> "На OpenWrt выключен учёт байтов соединений."
                sample?.status == "networks" -> "Шлюз не сообщил адреса локальных сетей."
                sample == null || sample.status == "warming" -> "Получаем счётчики шлюза…"
                else -> "Счётчики соединений шлюза сейчас недоступны."
            }
            val devices = remember(recorded) { recorded.sessionDevices() }
            var showDevices by remember { mutableStateOf(false) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val menuWidth = maxWidth
                PanelSecondaryButton(onClick = { showDevices = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (deviceFilter.isEmpty()) "Все устройства" else sessionDeviceLabel(deviceFilter, devices[deviceFilter]),
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = showDevices, onDismissRequest = { showDevices = false },
                    modifier = Modifier.width(menuWidth).heightIn(max = 280.dp),
                ) {
                    DropdownMenuItem(text = { Text("Все устройства") }, onClick = {
                        deviceFilter = ""
                        showDevices = false
                    })
                    for ((ip, name) in devices) {
                        DropdownMenuItem(text = { Text(sessionDeviceLabel(ip, name)) }, onClick = {
                            deviceFilter = ip
                            showDevices = false
                        })
                    }
                }
            }
            PanelTextField(
                destinationFilter, { destinationFilter = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Назначение: сервис, домен или IP") }, singleLine = true,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(fakeIpFilter == null, { fakeIpFilter = null }, label = { Text("Все маршруты") })
                FilterChip(fakeIpFilter == false, { fakeIpFilter = false }, label = { Text("Напрямую") })
                FilterChip(fakeIpFilter == true, { fakeIpFilter = true }, label = { Text("Через Fake-IP") })
            }
            val vertical = rememberLazyListState()
            LaunchedEffect(deviceFilter, destinationFilter, fakeIpFilter) { vertical.scrollToItem(0) }
            SessionConnectionTable(rows, vertical, message, sample?.offloaded == true || sample?.truncated == true)
        }
    }
}

@Composable
private fun SessionSummary(title: String, rates: SessionRates?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("↓ ${sessionSpeed(rates?.received)}  ·  ↑ ${sessionSpeed(rates?.sent)}", style = MaterialTheme.typography.labelMedium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private val ConnectionRowHeight = 34.dp
private val ConnectionHeaderHeight = 28.dp
private const val VisibleConnections = 15

@Composable
private fun SessionConnectionTable(rows: List<SessionConnection>, vertical: LazyListState, message: String?, partial: Boolean) {
    val horizontal = rememberScrollState()
    val thumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, 480.dp)
        Box(
            Modifier.fillMaxWidth().drawWithContent {
                drawContent()
                if (rows.size > VisibleConnections) {
                    val rowHeight = ConnectionRowHeight.toPx()
                    val viewportHeight = rowHeight * VisibleConnections
                    val thumbHeight = (viewportHeight * VisibleConnections / rows.size).coerceAtLeast(16.dp.toPx())
                    val firstRow = vertical.firstVisibleItemIndex + vertical.firstVisibleItemScrollOffset / rowHeight
                    val fraction = (firstRow / (rows.size - VisibleConnections)).coerceIn(0f, 1f)
                    val top = ConnectionHeaderHeight.toPx() + fraction * (viewportHeight - thumbHeight)
                    drawRoundRect(
                        color = thumb,
                        topLeft = Offset(size.width - 3.dp.toPx(), top),
                        size = Size(3.dp.toPx(), thumbHeight),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }.horizontalScroll(horizontal),
        ) {
            Column(Modifier.width(tableWidth)) {
                SessionConnectionRow("Устройство", "Назначение", "Маршрут", "↓ Приём", "↑ Передача", heading = true)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(ConnectionRowHeight * VisibleConnections),
                    state = vertical,
                ) {
                    if (message != null) item {
                        Text(message, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    items(rows, key = { it.id }) { row ->
                        val label = row.name ?: row.domain ?: row.destinationIp ?: row.observedIp ?: "—"
                        val destination = if (row.domain != null && row.destinationIp != null) "$label (${row.destinationIp})" else label
                        val device = sessionDeviceLabel(row.deviceIp, row.deviceName)
                        SessionConnectionRow(
                            device, destination,
                            if (row.viaFakeIp) "Через Fake-IP" else "Напрямую",
                            sessionSpeed(row.received), sessionSpeed(row.sent),
                        )
                    }
                }
            }
        }
    }
    Text(
        if (partial) "Последний замер · часть трафика может отсутствовать" else "Скорости по последнему замеру",
        Modifier.height(18.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionConnectionRow(
    device: String, destination: String, route: String,
    received: String, sent: String, heading: Boolean = false,
) {
    val colours = MaterialTheme.colorScheme
    val label = MaterialTheme.typography.labelSmall
    val muted = colours.onSurfaceVariant
    Column(Modifier.fillMaxWidth().height(if (heading) ConnectionHeaderHeight else ConnectionRowHeight)) {
        if (!heading) HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device, Modifier.weight(1f), style = label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (heading) FontWeight.Normal else FontWeight.SemiBold,
                color = if (heading) muted else colours.onSurface)
            Text(destination, Modifier.weight(1.1f), style = label, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(route, Modifier.width(76.dp), style = label, color = muted, maxLines = 1, softWrap = false)
            Text(received, Modifier.width(48.dp), style = label, color = if (heading) muted else colours.primary,
                textAlign = TextAlign.End, maxLines = 1, softWrap = false)
            Text(sent, Modifier.width(52.dp), style = label, color = if (heading) muted else colours.tertiary,
                textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        }
    }
}
