package com.bot4vps.mobile.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.JsonObject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class ServiceArchivesViewModel @Inject constructor(val repository: ServiceArchivesRepository): ViewModel() {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun act(block: suspend () -> Unit) { if (busy) return; viewModelScope.launch { busy = true; error = null; try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) } finally { busy = false } } }
    fun refresh() = act { repository.refreshInventory() }
}

@Composable
fun ServiceReserveCard(state: ServiceArchivesState, busy: Boolean, change: (Boolean?, Int?) -> Unit) {
    PanelCard("Копии сервисов на панели") {
        Text("Настройки общие с веб-панелью. Комплекты хранятся на панели и не загружаются на телефон.", style = MaterialTheme.typography.bodySmall)
        val settings = state.reserve
        if (settings == null) {
            if (state.settingsError == null) Text("Загрузка настроек…") else Text("Не удалось прочитать настройки", color = MaterialTheme.colorScheme.error)
        } else {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Резервировать автоматически", Modifier.weight(1f)); Switch(settings.enabled, { change(it, null) }, enabled = !busy && !state.saving && state.settingsError == null) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(2, 3).forEach { keep -> FilterChip(settings.keep == keep, { change(null, keep) }, enabled = !busy && !state.saving && state.settingsError == null, label = { Text("$keep версии") }) } }
        }
        state.settingsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun ServiceArchivesContent(modifier: Modifier = Modifier, vm: ServiceArchivesViewModel = hiltViewModel()) {
    val state by vm.repository.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ServiceArchiveGroup?>(null) }
    var entry by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(lifecycle, vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.repository.refreshInventory()
            while (true) { delay(5000); if (!vm.busy) vm.repository.refreshInventory() }
        }
    }
    val allGroups = serviceArchiveGroups(state.items)
    deleting?.let { selected ->
        val group = allGroups.find { it.service == selected.service && it.major == selected.major } ?: selected
        PanelAlertDialog(onDismissRequest = { if (!vm.busy) deleting = null },
            title = { Text("Удалить группу ${group.name} · ${group.platform}?") },
            text = { Text("С панели будут удалены все копии этой группы: ${group.entries.size}. Установленные сервисы и их настройки сохранятся.") },
            confirmButton = { TextButton(enabled = !vm.busy && group.removable, onClick = { vm.act { vm.repository.deleteGroup(group.service, group.deletionScope!!); deleting = null } }) { Text("Удалить") } },
            dismissButton = { TextButton(enabled = !vm.busy, onClick = { deleting = null }) { Text("Отмена") } })
    }
    entry?.let { selected ->
        val item = state.items.find { it.text("id") == selected.text("id") } ?: selected
        PanelAlertDialog(onDismissRequest = { if (!vm.busy) entry = null },
            title = { Text("Удалить ${item.text("name")} ${item.text("version")}? ") },
            text = { Text("Установочная копия будет удалена с панели. Сервис и его настройки сохранятся.") },
            confirmButton = { TextButton(enabled = !vm.busy && !item.flag("busy"), onClick = { vm.act { vm.repository.delete(item.text("id").orEmpty()); entry = null } }) { Text("Удалить") } },
            dismissButton = { TextButton(enabled = !vm.busy, onClick = { entry = null }) { Text("Отмена") } })
    }
    Refreshable(vm.busy || state.loading, vm::refresh, modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = refreshArrangement(8.dp)) {
            item { PanelSearchField(query, { query = it }, placeholder = "Поиск проекта или версии", modifier = Modifier.fillMaxWidth()) }
            state.inventoryError?.let { item { ErrorCard(it, vm::refresh) } }
            vm.error?.let { item { ErrorCard(it) } }
            val projects = allGroups.filter { group ->
                group.entries.any { "${group.name} ${group.platform} ${it.text("version")}".contains(query, ignoreCase = true) }
            }.groupBy { it.service }.toList()
            if (projects.isEmpty()) item { Text(if (state.loading) "Загрузка…" else "Копии сервисов не найдены") }
            items(projects, key = { it.first }) { (service, groups) ->
                var expanded by rememberSaveable(service) { mutableStateOf(false) }
                PanelCard {
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Свернуть проект" else "Развернуть проект")
                        Spacer(Modifier.width(8.dp))
                        Text(groups.first().name, fontWeight = FontWeight.SemiBold)
                    }
                    if (expanded) groups.forEach { group ->
                        key(group.major) {
                            var versionsExpanded by rememberSaveable(service, group.major) { mutableStateOf(false) }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { versionsExpanded = !versionsExpanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (versionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (versionsExpanded) "Свернуть версии" else "Развернуть версии")
                                    Spacer(Modifier.width(4.dp))
                                    Text("Версии для ${group.platform}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                                }
                                IconButton(onClick = { deleting = group }, enabled = !vm.busy && group.removable) {
                                    Icon(Icons.Default.DeleteOutline, "Удалить группу ${group.name} · ${group.platform}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (versionsExpanded) group.entries.filter { item ->
                                query.isBlank() || "${group.name} ${group.platform} ${item.text("version")}".contains(query, ignoreCase = true)
                            }.forEach { item ->
                                Row(Modifier.fillMaxWidth().padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(item.text("version") ?: "—", Modifier.weight(1f).horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false)
                                    val architecture = item.text("destination").orEmpty().substringAfter(" · ", "").substringBefore(" · ")
                                    Text(if (item.flag("busy")) "Используется" else if (!item.flag("complete")) "Неполная" else architecture, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(max = 110.dp).horizontalScroll(rememberScrollState()))
                                    IconButton(onClick = { entry = item }, enabled = !vm.busy && !item.flag("busy")) {
                                        Icon(Icons.Default.DeleteOutline, "Удалить ${group.name} ${item.text("version")}", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
