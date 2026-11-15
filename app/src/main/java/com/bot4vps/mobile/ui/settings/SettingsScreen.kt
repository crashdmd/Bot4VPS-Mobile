package com.bot4vps.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.bot4vps.mobile.BuildConfig
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.repository.AuthRepository
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.bot4vps.mobile.data.api.errorText
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val authRepository: AuthRepository, val preferences: PreferencesManager, val security: com.bot4vps.mobile.data.repository.SecurityRepository) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit, onChangeUrl: () -> Unit, onSection: (String) -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    var logoutBusy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val address by viewModel.authRepository.baseUrl.collectAsState(initial = null)
    val security by viewModel.security.state.collectAsState()
    var logoutError by remember { mutableStateOf<String?>(null) }
    logoutError?.let { message -> PanelAlertDialog(onDismissRequest = { logoutError = null },
        title = { Text("Не удалось отключить телефон") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { logoutError = null }) { Text("Ок") } }) }
    if (confirmLogout) PanelAlertDialog(onDismissRequest = { if (!logoutBusy) confirmLogout = false },
        title = { Text("Отключить этот телефон?") },
        text = { Text("$address\nПанель удалит телефон из подключённых устройств. Для повторного подключения потребуется новая привязка. Коды Authenticator сохранятся.\n" + com.bot4vps.mobile.data.model.primaryUnlinkWarning(security.isPrimary, security.deviceCount)) },
        confirmButton = { TextButton(enabled = !logoutBusy, onClick = {
            scope.launch { logoutBusy = true; try { viewModel.authRepository.logout(); confirmLogout = false; onLogout() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { confirmLogout = false; logoutError = errorText(e) }
            finally { logoutBusy = false } }
        }) { Text(if (logoutBusy) "Отключаем…" else "Отключить") } },
        dismissButton = { TextButton(enabled = !logoutBusy, onClick = { confirmLogout = false }) { Text("Отмена") } })
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Ещё", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Панель", style = MaterialTheme.typography.titleLarge) }
            item { ActionRow("Настройки панели", "Оформление, безопасность, Telegram, обновления и данные", Icons.Default.Tune, { onSection("settings") }) }
            item { Text("Инструменты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
            item { ActionRow("Мониторинг Bot4VPS", "Нагрузка хоста и состояние компонентов", Icons.Default.MonitorHeart, { onSection("monitor") }) }
            item { ActionRow("Файлы и SSH-ключи", "Скрипты, библиотека и ключи доступа", Icons.Default.FolderOpen, { onSection("files") }) }
            item { ActionRow("Графики серверов", "История нагрузки", Icons.Default.ShowChart, { onSection("metrics") }) }
            item { ActionRow("Аудит", "Журнал действий пользователей", Icons.Default.FactCheck, { onSection("audit") }) }
            item { ActionRow("Подключение", "Выбрать сервис или создать новую связь", Icons.Default.Link, onChangeUrl) }
            item { ActionRow("Отключить на этом телефоне", "Отвязать телефон от панели; коды Authenticator сохранятся", Icons.AutoMirrored.Filled.ExitToApp, { confirmLogout = true }, enabled = !logoutBusy, danger = true) }
            item { Text("Bot4VPS Mobile · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
