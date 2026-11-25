package com.bot4vps.mobile.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.repository.AuthRepository
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class PanelAddressViewModel @Inject constructor(val auth: AuthRepository) : ViewModel()

@Composable
fun PanelAddressDialog(onDismiss: () -> Unit, onSaved: () -> Unit, newPanel: Boolean = false, viewModel: PanelAddressViewModel = hiltViewModel()) {
    val savedProfiles by viewModel.auth.profiles.collectAsState(initial = emptyList())
    val profiles = savedProfiles.filter { !it.revoked && !it.localDisconnected }
    val current by viewModel.auth.baseUrl.collectAsState(initial = null)
    val currentOrigin = com.bot4vps.mobile.data.model.panelProfileOrigin(current)
    var page by remember { mutableStateOf(if (newPanel) "pair" else "menu") }
    var manual by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    var addressChange by remember { mutableStateOf<com.bot4vps.mobile.data.model.MobileConnectionChange?>(null) }
    var removing by remember { mutableStateOf<com.bot4vps.mobile.data.model.PanelProfile?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { try { viewModel.auth.refreshProfiles() } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { error = errorText(e) } }
    fun connect(code: String, allowPairing: Boolean = true) {
        if (busy) return
        busy = true; error = null
        attempt = scope.launch {
            try {
                routePanelConnectionCode(code,
                    { viewModel.auth.pairMobile(it); onSaved() },
                    { addressChange = viewModel.auth.previewAddressChange(it) }, allowPairing)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    val scanner = rememberQrScanner("Наведите камеру на QR в настройках Mobile API веб-панели", { connect(it) }, { error = it })
    fun preview(value: String) {
        if (value.trim().startsWith("b4vps:")) { connect(value, allowPairing = false); return }
        if (busy) return
        busy = true; error = null
        attempt = scope.launch {
            try { addressChange = viewModel.auth.previewAddressChange(value) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    val changeScanner = rememberQrScanner("Наведите камеру на QR смены Mobile API в веб-панели", { connect(it, allowPairing = false) }, { error = it })
    fun cancel() { attempt?.cancel(); onDismiss() }
    removing?.let { profile ->
        PanelAlertDialog(onDismissRequest = { if (!busy) removing = null }, title = { Text("Удалить подключение с телефона?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name); Text(profile.url)
                Text("Адрес, привязка и ключи этого подключения будут удалены с телефона. Связь с панелью не требуется. Для подключения заново понадобится новый код привязки.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; error = null
            attempt = scope.launch {
                try { viewModel.auth.removeLocalConnection(profile); removing = null }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { error = errorText(e) }
                finally { busy = false }
            }
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } }, dismissButton = {
            TextButton(enabled = !busy, onClick = { removing = null; error = null }) { Text("Отмена") }
        })
    }
    if (removing != null) return
    addressChange?.let { change -> PanelAlertDialog(onDismissRequest = { if (!busy) addressChange = null },
        title = { Text("Подтвердить новый адрес?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Прежний адрес: ${change.oldUrl}"); Text("Новый адрес: ${change.url}")
            Text("Подключение и коды 2FA будут перенесены после подтверждения сервиса.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; error = null
            attempt = scope.launch {
                try { viewModel.auth.changeAddress(change); addressChange = null; onSaved() }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { error = errorText(e) }
                finally { busy = false }
            }
        }) { Text(if (busy) "Подтверждаем…" else "Подтвердить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { addressChange = null; error = null }) { Text("Отмена") } }) }
    if (addressChange != null) return
    PanelAlertDialog(onDismissRequest = ::cancel, title = { Text(when (page) { "pair" -> "Новое подключение"; "select" -> "Выбрать сервис"; "address", "address_manual" -> "Изменить адрес панели"; else -> "Подключения" }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "menu" -> {
                    current?.let { Text("Текущий сервис: $it", style = MaterialTheme.typography.bodySmall) }
                    ActionRow("Выбрать сервис", "Сохранённые подключения; удерживайте строку для удаления", Icons.Default.SwapHoriz, { page = "select" }, enabled = !busy && profiles.isNotEmpty())
                    ActionRow("Изменить адрес панели", "Новый адрес существующего подключения", Icons.Default.Edit, { page = "address"; error = null },
                        enabled = !busy && profiles.any { com.bot4vps.mobile.data.model.panelProfileOrigin(it.url) == currentOrigin })
                    ActionRow("Новое подключение", "Сканировать QR или ввести код привязки", Icons.Default.AddLink, { page = "pair"; manual = false; draft = "" })
                }
                "select" -> {
                    if (profiles.isEmpty()) Text("Нет сохранённых подключений. Добавьте панель по QR или коду.")
                    else Text("Удерживайте строку сервиса, чтобы удалить его подключение с телефона.", style = MaterialTheme.typography.bodySmall)
                    profiles.forEach { profile -> ActionRow(profile.name,
                        if (profile.revoked) "Доступ отозван — создайте новую связь" else if (profile.localDisconnected) "Связь удалена с телефона — создайте новую" else if (com.bot4vps.mobile.data.model.panelProfileOrigin(profile.url) == currentOrigin) "Текущий сервис" else profile.url,
                        if (profile.revoked) Icons.Default.LinkOff else Icons.Default.Link, {
                            if (!busy) { busy = true; attempt = scope.launch {
                                try { viewModel.auth.selectProfile(profile.url); onSaved() }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
                                finally { busy = false }
                            } }
                        }, enabled = !busy, onLongClick = { removing = profile; error = null }) }
                }
                "pair" -> {
                    Text("Создайте привязку в Web: Настройки → Мобильное приложение. Отсканируйте QR.")
                    PanelButton(enabled = !busy, onClick = scanner, modifier = Modifier.fillMaxWidth()) { Text("Сканировать QR") }
                    TextButton(enabled = !busy, onClick = { manual = true }) { Text("Ввести код") }
                    if (manual) {
                        PanelTextField(draft, { draft = it; error = null }, label = { Text("Полный код b4vps:…") }, singleLine = true, enabled = !busy)
                        PanelButton(enabled = !busy && draft.isNotBlank(), onClick = { connect(draft.trim()) }) { Text("Создать связь") }
                    }
                }
                "address" -> {
                    Text("Начните смену Mobile API в веб-панели. Основной телефон должен подключиться по новым данным в течение двух минут.")
                    ActionRow("Сканировать QR с новым адресом", "QR из окна смены Mobile API", Icons.Default.QrCodeScanner, changeScanner, enabled = !busy)
                    ActionRow("Ввести вручную", "IP или домен и порт сервиса", Icons.Default.Edit, { draft = ""; page = "address_manual" }, enabled = !busy)
                }
                "address_manual" -> {
                    PanelTextField(draft, { draft = it; error = null }, label = { Text("IP или домен:порт") }, singleLine = true, enabled = !busy)
                    PanelButton(enabled = !busy && draft.isNotBlank(), onClick = { preview(draft.trim()) }) { Text("Проверить новый адрес") }
                }
            }
            if (busy) Text("Подключаемся к сервису…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {}, dismissButton = {
            if (page != "menu") TextButton(enabled = !busy, onClick = { page = "menu"; error = null }) { Text("Назад") }
            TextButton(onClick = ::cancel) { Text("Закрыть") }
        })
}
