package com.bot4vps.mobile.ui.scripts

import com.bot4vps.mobile.ui.components.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bot4vps.mobile.data.model.ScriptInfo
import com.bot4vps.mobile.data.model.visible
import com.bot4vps.mobile.data.model.scriptRunSummary
import androidx.compose.material.icons.filled.MoreVert
import com.bot4vps.mobile.ui.theme.*
import com.bot4vps.mobile.ui.control.NativeField
import kotlinx.serialization.json.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(onFiles: () -> Unit = {}, onOperation:(String)->Unit={}, onHistory:(String)->Unit={}, onChooseService: (() -> Unit)? = null, onBack: (() -> Unit)? = null, viewModel: ScriptsViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsState()
    LaunchedEffect(state.launchedTask) { state.launchedTask?.let { viewModel.taskOpened(); onHistory(it) } }
    var search by remember { mutableStateOf("") }

    BackHandler(enabled = onChooseService != null && state.activeScript == null) { onChooseService?.invoke() }
    if (state.activeScript != null) {
        RunDialog(state, viewModel)
    }

    PanelScaffold(
        topBar = {
            PanelTopBar(
                title = { Text("Скрипты", fontWeight = FontWeight.SemiBold, color = OnSurface) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } else onChooseService?.let { action -> TextButton(onClick = action) { Text("Сервисы") } } },
                actions = {
                    TextButton(onClick = onFiles) { Text("Файлы") }
                }
            )
        }
    ) { padding ->
        com.bot4vps.mobile.ui.components.Refreshable(state.isLoading, viewModel::refresh, Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            PanelSearchField(search, { search = it }, placeholder = "Поиск по названию…",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            state.message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = OnSurfaceVariant, fontSize = 13.sp)
            }
            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading && state.scripts.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Primary)
                    state.scripts.isEmpty() ->
                        Text("Нет скриптов", Modifier.align(Alignment.Center), color = TextDim)
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = refreshArrangement(10.dp)
                    ) {
                        if (state.scripts.none { search.isBlank() || it.name.contains(search.trim(), true) }) item { Text("Скрипты не найдены", color = TextDim) }
                        items(state.scripts.filter { search.isBlank() || it.name.contains(search.trim(), true) }, key = { it.name }) { script ->
                            ScriptCard(script, { viewModel.openRun(script) }, { onOperation(viewModel.file(script)) }, { onOperation(viewModel.file(script,true)) })
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ScriptCard(script: ScriptInfo, onRun: () -> Unit, onEdit:()->Unit, onDelete:()->Unit) {
    PanelSurfaceCard(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(script.name, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text("Параметров: ${script.params.size}", color = TextDim, style = MaterialTheme.typography.bodySmall)
                script.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            var menu by remember { mutableStateOf(false) }
            Box { IconButton(onClick={ menu=true }) { Icon(Icons.Default.MoreVert,"Действия скрипта") }
                DropdownMenu(menu,{ menu=false }) {
                    DropdownMenuItem(text={ Text("Редактировать") },onClick={ menu=false; onEdit() })
                    DropdownMenuItem(text={ Text("Удалить") },onClick={ menu=false; onDelete() })
                }
            }
            IconButton(onClick = onRun) { Icon(Icons.Default.PlayArrow, "Запустить скрипт", tint = Primary) }
        }
    }
}

@Composable
private fun RunDialog(state: ScriptsUiState, vm: ScriptsViewModel) {
    val script = state.activeScript ?: return
    AlertDialog(
        onDismissRequest = vm::closeRun,
        title = { Text(script.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val server = state.servers.find { it.id == state.selectedServerId }
                when (state.runStep) {
                    ScriptRunStep.SERVER -> {
                        Text("Выберите сервер для запуска скрипта.", style = MaterialTheme.typography.bodySmall)
                        SearchableChoice("Сервер", state.selectedServerId,
                            state.servers.map { it.id to listOfNotNull(it.name ?: it.id, it.host).joinToString(" · ") },
                            { it?.let(vm::selectServer) }, emptyLabel = "Выберите сервер", enabled = !state.busy)
                        if (state.servers.isEmpty()) Text("Нет серверов для запуска. Сначала добавьте сервер.")
                    }
                    ScriptRunStep.PARAMETERS -> {
                        Text("Сервер: ${server?.name ?: server?.id.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    if (script.params.isEmpty()) {
                    Text("Параметров нет — просто запуск", color = OnSurfaceVariant, fontSize = 13.sp)
                } else {
                    script.params.forEach { p ->
                        if(!p.visible(state.paramValues)) return@forEach
                        val schema = buildJsonObject {
                            put("type", when (p.type) { "bool" -> "boolean"; "number" -> "number"; else -> "string" })
                            put("title", p.label ?: p.name)
                            if(p.type == "password") put("secret",true)
                            if (p.type == "select") put("enum", JsonArray(p.options.map { JsonPrimitive(it.value) }))
                        }
                        val value = state.paramValues[p.name].orEmpty()
                        NativeField(p.name, schema, if (p.type == "bool") JsonPrimitive(value == "true") else JsonPrimitive(value), true,
                            { vm.setParam(p.name, (it as? JsonPrimitive)?.content.orEmpty()) },
                            if (p.type == "select") mapOf(p.name to p.options.map { it.value to it.label }) else emptyMap())
                    }
                }
                    }
                    ScriptRunStep.CONFIRM -> {
                        Text("Запустить скрипт на выбранном сервере?", style = MaterialTheme.typography.titleSmall)
                        InfoRow("Сервер", server?.name ?: server?.id.orEmpty())
                        InfoRow("Адрес", server?.host.orEmpty())
                        scriptRunSummary(script, state.paramValues).forEach { (name, value) -> InfoRow(name, value) }
                        if (script.params.isEmpty()) Text("Скрипт запускается без параметров.")
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            PanelButton(onClick = { if (state.runStep == ScriptRunStep.CONFIRM) vm.run() else vm.nextStep() },
                enabled = !state.busy && !state.isLoading && (state.runStep != ScriptRunStep.SERVER || state.selectedServerId != null)) {
                Text(when { state.busy -> "Запускаем…"; state.runStep == ScriptRunStep.SERVER -> "Далее";
                    state.runStep == ScriptRunStep.PARAMETERS -> "К подтверждению"; else -> "Запустить" })
            }
        },
        dismissButton = {
            Row {
                if (state.runStep != ScriptRunStep.SERVER) TextButton(onClick = vm::previousStep, enabled = !state.busy) { Text("Назад") }
                TextButton(onClick = vm::closeRun, enabled = !state.busy) { Text("Отмена") }
            }
        }
    )
}
