package com.bot4vps.mobile.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.repository.AuthRepository
import com.bot4vps.mobile.ui.components.PanelTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class PanelAddressViewModel @Inject constructor(val auth: AuthRepository) : ViewModel()

@Composable
fun PanelAddressDialog(onDismiss: () -> Unit, onSaved: () -> Unit, newPanel: Boolean = false, viewModel: PanelAddressViewModel = hiltViewModel()) {
    val current by viewModel.auth.baseUrl.collectAsState(initial = null)
    var draft by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(current) { if (!edited && !newPanel) draft = current.orEmpty() }
    fun cancel() { attempt?.cancel(); onDismiss() }
    AlertDialog(onDismissRequest = ::cancel,
        title = { Text(if (newPanel) "Другая панель" else "Изменить адрес") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Отсканируйте QR или вставьте полный код из Web-панели другого сервера. Для ранее привязанного сервера можно выбрать его HTTPS-адрес API.")
            QrScanButton(enabled = !busy, onCode = { draft = it; edited = true; error = null }, onError = { error = it })
            PanelTextField(draft, { draft = it; edited = true; error = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Адрес") }, placeholder = { Text("b4vps:… или https://сервер:8444") }, singleLine = true,
                enabled = !busy, isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) Text("Подключаемся… Ожидаем ответ не дольше 10 секунд.")
        } },
        confirmButton = { TextButton(enabled = !busy && draft.isNotBlank(), onClick = {
            attempt = scope.launch {
                busy = true
                try { if (draft.trim().startsWith("b4vps:")) viewModel.auth.pairMobile(draft.trim()) else viewModel.auth.setBaseUrl(draft); onSaved() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = errorText(e) }
                finally { busy = false }
            }
        }) { Text(if (busy) "Сохранение…" else "Сохранить") } },
        dismissButton = { TextButton(onClick = ::cancel) { Text("Отмена") } })
}
