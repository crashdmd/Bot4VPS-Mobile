package com.bot4vps.mobile.ui.gateways

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.*
import kotlinx.serialization.json.JsonObject

private fun wifiColor(id: String) = WifiColors[(id.hashCode().toLong() and 0x7fffffff).toInt() % WifiColors.size]
private val WifiColors = listOf(Color(0xff6db6ff), Color(0xff70cfac), Color(0xffd1a0ef), Color(0xffe6b968), Color(0xffee8da8), Color(0xff87d6dd), Color(0xffb8cd78))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessScanDialog(title: String, data: JsonObject, dismiss: () -> Unit) {
    val scan = remember(data) { wifiScan(data) }
    var selected by remember(data) { mutableStateOf<String?>(null) }
    val bands = remember(scan) { scan.channels.map { if (it.frequency < 3000) "2g" else if (it.frequency >= 5925) "6g" else "5g" }.distinct() }
    var band by remember(data) { mutableStateOf(bands.firstOrNull() ?: "2g") }
    fun inBand(frequency: Double) = (if (frequency < 3000) "2g" else if (frequency >= 5925) "6g" else "5g") == band
    val channels = scan.channels.filter { inBand(it.frequency) }
    val peers = scan.networks.filter { it.frequency?.let(::inBand) == true && it.signal != null }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        PanelScaffold(topBar = { PanelTopBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bands.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bands.forEach { value -> FilterChip(selected = band == value, onClick = { band = value; selected = null }, label = { Text(if (value == "2g") "2,4 ГГц" else if (value == "6g") "6 ГГц" else "5 ГГц") }) }
                }
                PanelCard("Каналы и сигнал сетей") {
                    WifiChart(peers, channels, scan.currentFrequency, selected, false)
                    Text("Цветной контур — сеть: высота показывает уровень сигнала, ширина — полосу. Пунктир — текущий канал.", style = MaterialTheme.typography.bodySmall)
                    if (peers.any { row -> row.segments.any { !it.known } }) Text("Для сетей с неизвестной шириной показан контур 20 МГц.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (peers.isEmpty()) Text(if (scan.networks.isEmpty()) "Соседние сети не найдены" else "Драйвер не сообщил частоту или уровень сигнала", style = MaterialTheme.typography.bodySmall)
                    peers.forEach { peer ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (selected == peer.id) null else peer.id }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(9.dp).background(wifiColor(peer.id)))
                            Text(peer.ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text("${peer.signal?.toInt()} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                PanelCard("Загрузка эфира") {
                    if (channels.any { it.busy != null }) {
                        WifiChart(emptyList(), channels, scan.currentFrequency, null, true)
                        Text("Столбики — доля времени, когда эфир был занят, в процентах. Пробел — нет измерения. Пунктир — текущий канал.", style = MaterialTheme.typography.bodySmall)
                        Text("Загрузка помогает сравнить каналы; она не запрещает использовать канал.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("Драйвер не предоставил измерения загрузки эфира", style = MaterialTheme.typography.bodySmall)
                }
                Text("Найденные сети и точки доступа · ${scan.networks.size}", style = MaterialTheme.typography.titleMedium)
                scan.networks.forEach { peer -> PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(9.dp).background(wifiColor(peer.id)))
                        Text(peer.ssid, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    InfoRow("Канал / сигнал", "${peer.channel ?: "—"} · ${peer.signal?.toInt()?.let { "$it dBm" } ?: "Нет измерения"}")
                    InfoRow("BSSID", peer.bssid.ifEmpty { "—" })
                    InfoRow("Защита", if (peer.protected) "Включена" else "Открытая сеть")
                    channels.firstOrNull { it.frequency == peer.frequency }?.busy?.let { InfoRow("Загрузка эфира", "%.1f %%".format(it)) }
                } }
                if (scan.networks.isEmpty()) Text("Сети не обнаружены")
            }
        }
    }
}

@Composable
private fun WifiChart(networks: List<WifiNetwork>, channels: List<WifiChannel>, current: Double?, selected: String?, occupancy: Boolean) {
    val text = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val bar = MaterialTheme.colorScheme.primary
    val values = channels.map { it.frequency } + networks.flatMap { it.segments.flatMap { segment -> listOf(segment.center - segment.width / 2, segment.center + segment.width / 2) } }
    val from = (values.minOrNull() ?: 2400.0) - 5
    val to = (values.maxOrNull() ?: 2490.0) + 5
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, (channels.size * 24 + 70).dp)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.width(chartWidth).height(225.dp)) {
                val left = 40.dp.toPx(); val right = size.width - 14.dp.toPx(); val top = 20.dp.toPx(); val base = size.height - 35.dp.toPx()
                fun x(frequency: Double) = left + ((frequency - from) / (to - from)).toFloat() * (right - left)
                fun y(amount: Double) = base - ((if (occupancy) amount.coerceIn(0.0, 100.0) / 100 else (amount.coerceIn(-100.0, -20.0) + 100) / 80).toFloat()) * (base - top)
                repeat(5) { i ->
                    val amount = if (occupancy) i * 25.0 else -100 + i * 20.0
                    val at = y(amount)
                    drawLine(grid, Offset(left, at), Offset(right, at))
                    drawText(text, amount.toInt().toString(), Offset(0f, at - 6.dp.toPx()), style = TextStyle(color = label, fontSize = 10.sp))
                }
                drawText(text, if (occupancy) "%" else "dBm", Offset(0f, 0f), style = TextStyle(color = label, fontSize = 10.sp))
                channels.forEach { channel ->
                    val at = x(channel.frequency)
                    if (occupancy && channel.busy != null) {
                        val width = minOf(16.dp.toPx(), (right - left) / maxOf(channels.size, 1) * .6f)
                        drawRect(bar.copy(alpha = .65f), Offset(at - width / 2, y(channel.busy)), Size(width, maxOf(1.dp.toPx(), base - y(channel.busy))))
                    }
                    val measured = text.measure((channel.channel ?: channel.frequency.toInt()).toString(), TextStyle(color = label, fontSize = 10.sp))
                    drawText(measured, topLeft = Offset(at - measured.size.width / 2, base + 8.dp.toPx()))
                }
                current?.takeIf { it in from..to }?.let { drawLine(marker, Offset(x(it), top), Offset(x(it), base), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))) }
                networks.sortedBy { it.signal }.forEach { peer ->
                    val color = wifiColor(peer.id).copy(alpha = if (selected == null || selected == peer.id) 1f else .2f)
                    peer.segments.forEach { segment ->
                        val a = x(segment.center - segment.width / 2); val b = x(segment.center + segment.width / 2); val peak = y(peer.signal ?: -100.0)
                        val path = Path().apply { moveTo(a, base); lineTo(a + (b - a) / 4, peak); lineTo(b - (b - a) / 4, peak); lineTo(b, base); close() }
                        drawPath(path, color.copy(alpha = color.alpha * .13f)); drawPath(path, color, style = Stroke(if (selected == peer.id) 3.dp.toPx() else 1.5.dp.toPx()))
                    }
                }
                drawText(text, "Канал", Offset(right - 38.dp.toPx(), size.height - 12.dp.toPx()), style = TextStyle(color = label, fontSize = 10.sp))
            }
        }
    }
}
