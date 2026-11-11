package com.bot4vps.mobile.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bot4vps.mobile.ui.components.*
import com.bot4vps.mobile.ui.theme.*

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onChangeUrl: () -> Unit, onOtherPanel: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var manualCode by remember { mutableStateOf(false) }
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onLoggedIn() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelConnection() } }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(12.dp))
            .border(1.dp, OutlineVariant, RoundedCornerShape(12.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bot4VPS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OnSurface)
            Text(if (state.pairingRequired) "Привязка телефона" else "Подключение к серверу", fontSize = 14.sp, color = TextDim)
            state.address?.let { Text(it, color = OnSurfaceVariant, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
            if (state.isLoading) Text("Подключаемся к панели… Ожидаем ответ не дольше 10 секунд.",
                color = OnSurfaceVariant, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            if (state.pairingRequired) {
                Text("Создайте привязку в Web-панели: Настройки → Мобильное приложение. Отсканируйте QR или введите полный код.",
                    color = OnSurfaceVariant, fontSize = 13.sp)
                QrScanButton(enabled = !state.isLoading, onCode = viewModel::pairScannedCode, onError = viewModel::onPairingError)
                TextButton(enabled = !state.isLoading, onClick = { manualCode = true }) { Text("Ввести код") }
                if (manualCode) PanelTextField(state.pairingCode, viewModel::onPairingCode,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Код привязки b4vps:…") },
                    singleLine = true, enabled = !state.isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.pairPhone() }))
            } else Text("Приложение проверяет ключ привязанного телефона автоматически.", color = OnSurfaceVariant, fontSize = 13.sp)
            if (state.pairingSuccess) Text("Телефон успешно привязан", color = OnSurfaceVariant, fontSize = 13.sp)
            state.error?.let { Text(it, color = Error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()) }
            PanelButton(onClick = { if (state.pairingRequired) viewModel.pairPhone() else viewModel.reconnect() },
                enabled = !state.isLoading && (!state.pairingRequired || manualCode),
                modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (state.isLoading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(22.dp))
                else Text(if (state.pairingRequired) "Привязать телефон" else "Повторить подключение", fontWeight = FontWeight.SemiBold)
            }
            if (!state.pairingRequired) TextButton(enabled = !state.isLoading, onClick = viewModel::showPairing) { Text("Привязать телефон заново") }
            TextButton(onClick = { viewModel.cancelConnection(); onChangeUrl() }) { Text("Изменить адрес", color = OnSurfaceVariant) }
            TextButton(onClick = { viewModel.cancelConnection(); onOtherPanel() }) { Text("Подключиться к другой панели", color = OnSurfaceVariant) }
        }
    }
}
