package com.bot4vps.mobile.ui.terminal

import com.bot4vps.mobile.ui.components.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(serverId: String, sessions: TerminalSessionsViewModel, onBack: () -> Unit) {
    val session = remember(serverId, sessions) { sessions.session(serverId) }
    val owner = remember(session) { Any() }
    val status by session.status.collectAsStateWithLifecycle()
    val revision by session.revision.collectAsStateWithLifecycle()
    var confirmClose by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf<NativeTerminalView?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(terminal, revision) { terminal?.invalidate() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) session.resume(owner)
            if (event == Lifecycle.Event.ON_PAUSE) { terminal?.hideKeyboard(); keyboard?.hide(); session.pause(owner) }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.resume(owner)
        onDispose { terminal?.hideKeyboard(); keyboard?.hide(); lifecycle.removeObserver(observer); session.pause(owner) }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text("Завершить SSH-сессию?") },
        text = { Text("Соединение закроется сразу. При следующем открытии терминала начнётся новая сессия.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; session.close(); terminal?.hideKeyboard(); keyboard?.hide(); onBack() }) { Text("Завершить") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Отмена") } })
    PanelScaffold(topBar = { PanelTopBar(title = { Text("SSH-терминал") }, navigationIcon = { TextButton(onClick = { terminal?.hideKeyboard(); keyboard?.hide(); onBack() }) { Text("Назад") } }, actions = { IconButton(onClick = { confirmClose = true }) { Icon(Icons.Default.Close, "Завершить сессию") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(status.message, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                if (status.canConnect) TextButton(onClick = session::connect) { Text("Подключить") }
            }
            AndroidView(factory = { context -> NativeTerminalView(context, session.buffer).apply { onInput = session::send; onResize = session::resize; input.onCtrlChanged = { ctrl = it }; terminal = this } }, modifier = Modifier.weight(1f).fillMaxWidth())
            Text("При возврате в течение 5 минут продолжится эта сессия.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { terminal?.input?.toggleCtrl() }, contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (ctrl) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) { Text(if (ctrl) "Ctrl ✓" else "Ctrl") }
                listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Enter" to "\r").forEach { (label, value) ->
                    TextButton(onClick = { terminal?.sendInput(value) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) }
                }
            }
        }
    }
}
