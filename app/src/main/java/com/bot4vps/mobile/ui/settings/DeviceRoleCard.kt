package com.bot4vps.mobile.ui.settings

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.repository.*
import com.bot4vps.mobile.ui.components.*
import com.bot4vps.mobile.ui.security.securityTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class DeviceRoleViewModel @Inject constructor(private val security: SecurityRepository, private val management: PhoneManagementRepository,
    private val prefs: PreferencesManager) : ViewModel() {
    var state by mutableStateOf<SecuritySnapshot?>(null); private set
    var phones by mutableStateOf(emptyList<ManagedPhone>()); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var invitation by mutableStateOf<PhoneInvitation?>(null); private set
    var invitationState by mutableStateOf("pending"); private set
    var managementAvailable by mutableStateOf(false); private set
    private var invitationOrigin: String? = null
    private var invitationPoll: Job? = null
    suspend fun refresh() {
        val base = prefs.baseUrl.first() ?: return
        val origin = mobileOrigin(PanelUrl.endpoint(base, "/api/mobile/security"))
        try {
            val result = loadDeviceRole(origin, security::refreshFor, management::phones)
            if (prefs.baseUrl.first() != base) return
            state = result.snapshot
            phones = result.phones
            managementAvailable = result.managementAvailable
            if (!result.snapshot.isPrimary) closeInvitation()
            error = result.error
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            if (state?.origin != origin) { state = null; phones = emptyList() }
            managementAvailable = false; closeInvitation(); error = errorText(e)
        }
    }
    fun retry() { viewModelScope.launch { refresh() } }
    fun revoke(phone: ManagedPhone) {
        val origin = state?.takeIf { it.isPrimary }?.origin ?: return
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { management.revoke(origin, phone.id); message = "Доступ телефона «${phone.name}» отозван"; refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    fun invite(name: String) {
        val origin = state?.takeIf { it.isPrimary }?.origin ?: return
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                invitation = management.invite(origin, name); invitationOrigin = origin; invitationState = "pending"
                invitationPoll?.cancel()
                invitationPoll = viewModelScope.launch {
                    while (invitation != null && invitationState == "pending") {
                        delay(2000)
                        try {
                            if (prefs.baseUrl.first()?.let { mobileOrigin(PanelUrl.endpoint(it, "/api/me")) } != origin) { closeInvitation(); return@launch }
                            invitationState = management.invitationStatus(origin, invitation!!.statusToken)
                            if (invitationState == "paired") { message = "Новый телефон подключён"; refresh() }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { error = errorText(e); closeInvitation() }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    fun closeInvitation() { invitationPoll?.cancel(); invitationPoll = null; invitation = null; invitationOrigin = null }
}

@Composable
fun DeviceRoleCard(viewModel: DeviceRoleViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { viewModel.refresh(); delay(5000) } } }
    var revoke by remember { mutableStateOf<ManagedPhone?>(null) }
    var add by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    SettingsCard("Роль мобильного устройства") {
        val state = viewModel.state
        if (state == null) Text(if (viewModel.error == null) "Проверяем роль на сервисе…" else "Не удалось узнать роль устройства")
        else if (state.supported != true) Text("Обновите панель для управления ролью телефона")
        else {
            StatusBadge(if (state.isPrimary) "Основной телефон" else "Обычный телефон", if (state.isPrimary) StatusTone.GOOD else StatusTone.NEUTRAL)
            Text(if (state.isPrimary) "Подтверждает вход в панель при 2FA, управляет подключёнными телефонами, может отменить отзыв доступа и подтверждает смену Mobile API. Хранит код 2FA панели и получает уведомления о новых телефонах и входах с новых IP."
                else "Работа с серверами и сервисами. Чувствительные действия подтверждает основной телефон; код 2FA панели хранится у него. При запросе отзыва вашего доступа придёт уведомление — для отмены обратитесь к администратору.", style = MaterialTheme.typography.bodySmall)
            if (!state.isPrimary && state.primaryDeviceId == null) Text("Основной телефон не назначен. Администратор может выбрать его в веб-панели.")
            if (state.isPrimary) {
                Text("Подключённые телефоны", style = MaterialTheme.typography.titleSmall)
                viewModel.phones.forEach { phone ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(phone.name)
                            Text(if (phone.isPrimary) "Этот телефон · основной" else "Подключён: ${securityTime(phone.created)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!phone.isPrimary) TextButton(enabled = !viewModel.busy && viewModel.managementAvailable, onClick = { revoke = phone }) { Text("Отозвать") }
                    }
                }
                PanelButton(enabled = !viewModel.busy && viewModel.managementAvailable, onClick = { add = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Добавить телефон") }
            }
        }
        viewModel.error?.let { ErrorCard(it, viewModel::retry) }
        viewModel.message?.let { Text(it) }
    }
    revoke?.let { phone -> PanelAlertDialog(onDismissRequest = { revoke = null }, title = { Text("Отозвать доступ телефона?") },
        text = { Text("Телефон «${phone.name}» будет отключён от этого сервиса. Для возврата понадобится новая привязка.") },
        confirmButton = { TextButton(onClick = { revoke = null; viewModel.revoke(phone) }) { Text("Отозвать доступ") } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text("Отмена") } }) }
    if (add) PanelAlertDialog(onDismissRequest = { add = false }, title = { Text("Подключить другой телефон?") },
        text = { Text("Создадим QR-код для привязки. Другой телефон автоматически передаст своё имя и получит обычную роль.") },
        confirmButton = { TextButton(onClick = { add = false; viewModel.invite("Телефон") }) { Text("Создать QR-код") } },
        dismissButton = { TextButton(onClick = { add = false }) { Text("Отмена") } })
    viewModel.invitation?.let { invitation ->
        var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
        LaunchedEffect(invitation) { while (true) { now = System.currentTimeMillis() / 1000.0; delay(1000) } }
        val expired = now >= invitation.expires
        PanelAlertDialog(onDismissRequest = viewModel::closeInvitation, title = { Text("Привязка другого телефона") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    viewModel.invitationState == "paired" -> Text("Новый телефон подключён к сервису")
                    expired || viewModel.invitationState == "expired" -> Text("Код истёк. Создайте новый QR для привязки.")
                    else -> {
                        Text("На другом телефоне откройте «Ещё → Подключение → Создать связь с сервисом» и отсканируйте QR. Новый телефон получит обычную роль.")
                        val bitmap = remember(invitation.qr) { runCatching { val bytes = Base64.decode(invitation.qr.substringAfter(','), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
                        bitmap?.let { Image(it.asImageBitmap(), "QR привязки нового телефона", Modifier.fillMaxWidth().height(210.dp)) }
                        Text("Код действует ещё ${(invitation.expires - now).toLong().coerceAtLeast(0) / 60} мин")
                        TextButton(onClick = { manual = true }) { Text("Передать данные для ручного ввода") }
                        if (manual) {
                            Text("Передавайте только владельцу нового телефона. Полный код вводится через «Ввести код».")
                            SelectionContainer { Text(invitation.code, style = MaterialTheme.typography.bodySmall) }
                            PanelButton(onClick = { clipboard.setText(AnnotatedString(invitation.code)); copied = true }) { Text(if (copied) "Скопировано" else "Копировать код") }
                        }
                    }
                }
            } }, confirmButton = { TextButton(onClick = viewModel::closeInvitation) { Text("Закрыть") } })
    }
}
