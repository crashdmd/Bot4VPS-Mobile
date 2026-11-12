package com.bot4vps.mobile.ui.services

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.*
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerChecksScreen(onBack: () -> Unit, onServer: (String) -> Unit,
    viewModel: ServerChecksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val run = state.current
    val kind = state.selected
    DisposableEffect(viewModel) {
        viewModel.hideResults()
        onDispose { viewModel.hideResults() }
    }
    LaunchedEffect(kind) { viewModel.refresh() }
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Проверки серверов") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        actions = {
            if (state.visibleResults.isNotEmpty()) IconButton(onClick = viewModel::sort) {
                Icon(if (run.sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    "Сортировать ${if (kind == ServerCheckKind.ONLINE) "по пингу" else "по сроку действия"}: ${if (run.sortAscending) "от большего к меньшему" else "от меньшего к большему"}")
            }
        }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerCheckKind.entries.forEach { tab -> PanelTab(kind == tab, { viewModel.select(tab) },
                    modifier = Modifier.weight(1f), label = { Text(tab.title) }) }
            }
        Refreshable(run.refreshing, viewModel::refresh, Modifier.weight(1f), enabled = !run.running) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = refreshArrangement(12.dp)) {
            item { PanelCard {
                Text(if (kind == ServerCheckKind.ONLINE) "Пинг всех серверов" else "Проверка SSL-сертификатов",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (kind == ServerCheckKind.ONLINE) "Проверка доступности с сервера Bot4VPS." else
                    "Проверяются сертификаты серверов, для которых включена SSL-проверка.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Протяжка обновляет результаты. Кнопка запускает новую проверку.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = viewModel::run, enabled = !run.running && !run.refreshing, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (kind == ServerCheckKind.ONLINE) Icons.Default.NetworkCheck else Icons.Default.VerifiedUser, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (run.running) "Проверяем…" else "Проверить все")
                }
                if (run.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                run.completedAt?.let { Text("Последняя проверка: ${displayTime(Instant.ofEpochMilli(it).toString())}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                run.report?.takeIf { run.completedAt != null }?.summaryLines(kind)?.forEach { line -> Text(line,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.hasResults && !run.resultsVisible) TextButton(onClick = viewModel::showResults,
                    enabled = !run.running && !run.refreshing) { Text("Результаты последней проверки") }
            } }
            run.error?.let { message -> item { ErrorCard(message, viewModel::refresh) } }
            val rows = state.visibleResults
            if (rows.isNotEmpty()) {
                item { Text("Результаты · ${rows.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.id }) { row -> CheckResultCard(row, kind) { onServer(row.id) } }
            }
        }
        }
        }
    }
}

@Composable
private fun CheckResultCard(row: ServerCheckRow, kind: ServerCheckKind, onServer: () -> Unit) {
    PanelCard {
        Text(row.name ?: row.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (kind == ServerCheckKind.ONLINE) {
            StatusBadge(when (row.available) { true -> "В сети"; false -> "Офлайн"; null -> "Неизвестно" },
                when (row.available) { true -> StatusTone.GOOD; false -> StatusTone.BAD; null -> StatusTone.NEUTRAL })
            row.latencyMs?.let { Text("${"%.1f".format(it)} мс · ${row.method}") }
        } else {
            StatusBadge(when (row.status) { "valid" -> "Действует"; "warning" -> "Скоро истекает"; "expired" -> "Истёк";
                "skip" -> "Проверка не настроена"; "error" -> "Ошибка проверки"; else -> "Нет результата" },
                when (row.status) { "valid" -> StatusTone.GOOD; "warning" -> StatusTone.WARNING;
                    "expired", "error" -> StatusTone.BAD; else -> StatusTone.NEUTRAL })
            row.daysLeft?.let { Text(if (it < 0) "Истёк ${-it.toLong()} дн. назад" else "Осталось дней: $it") }
            if (row.expires.isNotBlank()) Text("Срок действия: ${displayTime(row.expires)}", style = MaterialTheme.typography.bodySmall)
            if (row.checked.isNotBlank()) Text("Проверен: ${displayTime(row.checked)}", style = MaterialTheme.typography.labelSmall)
        }
        if (row.error.isNotBlank()) SelectionContainer { Text(row.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (row.sshError.isNotBlank()) Text("SSH: ${row.sshError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onServer) { Text("Открыть сервер →") }
    }
}
