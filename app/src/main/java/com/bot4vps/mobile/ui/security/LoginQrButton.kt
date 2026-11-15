package com.bot4vps.mobile.ui.security

import com.bot4vps.mobile.ui.components.PanelAlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.SecurityRepository
import com.bot4vps.mobile.ui.auth.rememberQrScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltViewModel
class LoginQrViewModel @Inject constructor(private val security: SecurityRepository) : ViewModel() {
    var qr by mutableStateOf<MobileLoginQr?>(null); private set
    var preview by mutableStateOf<SecurityRequest?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    fun scan(value: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { val data = readLoginQr(value); val request = security.loginPreview(data); qr = data; preview = request }
            catch (e: CancellationException) { throw e } catch (e: Exception) { message = errorText(e) }
            finally { busy = false }
        }
    }
    fun approve() {
        val value = qr ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { security.approveQr(value); message = "Вход подтверждён. Вернитесь в браузер." }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = if (e is java.io.IOException && e !is com.bot4vps.mobile.data.api.ApiError && e !is com.bot4vps.mobile.data.api.MobileAccessRevoked) "Результат подтверждения неизвестен. Проверьте вход в браузере; если он не завершён, создайте новый QR после восстановления связи." else errorText(e)
            }
            finally { busy = false; qr = null; preview = null }
        }
    }
    fun cancel() { if (!busy) { qr = null; preview = null } }
    fun error(value: String) { message = value }
    fun dismissMessage() { message = null }
}

@Composable
fun LoginQrButton(viewModel: LoginQrViewModel = hiltViewModel()) {
    val scan = rememberQrScanner("Наведите камеру на QR входа в Web-панель", viewModel::scan, viewModel::error)
    IconButton(enabled = !viewModel.busy, onClick = scan) { Icon(Icons.Default.QrCodeScanner, "Сканировать QR для входа") }
    viewModel.qr?.let { qr -> PanelAlertDialog(onDismissRequest = viewModel::cancel,
        title = { Text("Подтвердить вход в Web?") },
        text = { Column { Text(qr.origin); viewModel.preview?.let { SecurityRequestDetails(it, System.currentTimeMillis() / 1000.0) } } },
        confirmButton = { TextButton(enabled = !viewModel.busy, onClick = viewModel::approve) { Text(if (viewModel.busy) "Подтверждаем…" else "Подтвердить вход") } },
        dismissButton = { TextButton(enabled = !viewModel.busy, onClick = viewModel::cancel) { Text("Отмена") } }) }
    viewModel.message?.let { PanelAlertDialog(onDismissRequest = viewModel::dismissMessage,
        title = { Text("Вход по QR") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("Ок") } }) }
}
