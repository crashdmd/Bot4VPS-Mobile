package com.bot4vps.mobile.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.GroupRepository
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(val repo: GroupRepository, val prefs: PreferencesManager) : ViewModel() {
    val groups = repo.groups
    val display = prefs.groupDisplay.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GroupDisplaySettings())
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    init { refresh() }
    fun clearFeedback() { error = null; notice = null }
    fun refresh() = perform { repo.refresh() }
    fun save(old: ServerGroup?, name: String, shown: Boolean, done: () -> Unit = {}) = perform {
        val trimmed = name.trim()
        val remoteChange = old == null || old.name != trimmed
        if (remoteChange) {
            repo.save(old?.name, trimmed)
        }
        try {
            prefs.editGroupDisplay { it.shown(trimmed, shown) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!remoteChange) throw e
            notice = "Группа сохранена, но видимость не удалось изменить. Откройте редактирование и повторите."
            done()
            return@perform
        }
        notice = if (old == null) "Группа создана" else "Группа сохранена"
        done()
    }
    fun delete(group: ServerGroup) = perform { repo.delete(group); notice = "Группа удалена" }
    fun ssl(group: ServerGroup, enabled: Boolean) = perform {
        repo.setSslMonitor(group, enabled)
        notice = if (enabled) "SSL-проверка включена" else "SSL-проверка выключена"
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(onBack: () -> Unit, viewModel: GroupsViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerGroup?>(null) }
    var deleting by remember { mutableStateOf<ServerGroup?>(null) }
    val snack = remember { SnackbarHostState() }
    fun edit(group: ServerGroup?) { viewModel.clearFeedback(); editing = group; editorOpen = true }
    LaunchedEffect(viewModel.notice, viewModel.error) {
        (viewModel.error ?: viewModel.notice)?.let { snack.showSnackbar(it) }
    }
    if (editorOpen) GroupEditor(editing, groups.orEmpty(), editing?.name?.let { it !in display.hidden } ?: true, viewModel.busy, viewModel.error,
        onDismiss = { editorOpen = false },
        onSave = { name, shown -> viewModel.save(editing, name, shown) { editorOpen = false } })
    deleting?.let { group ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить группу?") },
            text = { Text("Пустая группа «${group.name}» будет удалена из сервиса.") },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(group) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
    PanelScaffold(topBar = {
        PanelTopBar(title = { Text("Настройки групп") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }, actions = {
            IconButton(enabled = groups != null && !viewModel.busy, onClick = { edit(null) }) {
                Icon(Icons.Default.Add, "Добавить группу")
            }
        })
    }, snackbarHost = { SnackbarHost(snack) }) { padding ->
        Refreshable(viewModel.busy, { viewModel.refresh() }, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = refreshArrangement(12.dp)) {
                if (groups == null) {
                    item {
                        if (viewModel.busy) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else ErrorCard(viewModel.error ?: "Не удалось загрузить группы", viewModel::refresh)
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Группы серверов · ${groups.orEmpty().size}", style = MaterialTheme.typography.titleMedium)
                            Text("SSL-проверки общие для всех устройств. Видимость группы меняется через «Редактировать».",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (groups.orEmpty().isEmpty()) item {
                        ActionRow("Создать первую группу", "Объединяйте серверы по назначению", Icons.Default.CreateNewFolder,
                            onClick = { edit(null) }, enabled = !viewModel.busy)
                    }
                    items(groups.orEmpty(), key = { it.name }) { group ->
                        GroupCard(group, group.name !in display.hidden, viewModel.busy,
                            onSsl = { viewModel.ssl(group, it) }, onEdit = { edit(group) }, onDelete = { deleting = group })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: ServerGroup, visible: Boolean, busy: Boolean, onSsl: (Boolean) -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Серверов: ${group.servers}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!visible) Text("Скрыта в списке групп", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия с группой «${group.name}»") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Редактировать") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                        enabled = !busy, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        enabled = group.servers == 0 && !busy, onClick = { menu = false; onDelete() })
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Проверять SSL", style = MaterialTheme.typography.titleSmall)
                Text("Следить за сроком действия сертификатов серверов этой группы.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = group.ssl_monitor, onCheckedChange = onSsl, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Проверять SSL группы «${group.name}»" })
        }
    }
}

@Composable
private fun GroupEditor(group: ServerGroup?, groups: List<ServerGroup>, visible: Boolean, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by rememberSaveable(group?.name) { mutableStateOf(group?.name.orEmpty()) }
    var shown by rememberSaveable(group?.name) { mutableStateOf(visible) }
    val duplicate = groups.any { it.name == name.trim() && it.name != group?.name }
    val changed = group == null || name.trim() != group.name || shown != visible
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (group == null) "Новая группа" else "Редактировать группу") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PanelTextField(value = name, onValueChange = { name = it }, label = { Text("Название группы") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, isError = duplicate,
                    supportingText = { if (duplicate) Text("Группа с таким названием уже есть") })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Показывать группу в списке", style = MaterialTheme.typography.titleSmall)
                        Text("На экране «Серверы» этого телефона. Серверы группы остаются доступны в общем списке.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = shown, onCheckedChange = { shown = it }, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Показывать группу в списке серверов" })
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = changed && name.isNotBlank() && !duplicate && !busy, onClick = { onSave(name.trim(), shown) }) {
                Text(if (busy) "Сохранение…" else if (group == null) "Создать" else "Сохранить")
            }
        }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } })
}
