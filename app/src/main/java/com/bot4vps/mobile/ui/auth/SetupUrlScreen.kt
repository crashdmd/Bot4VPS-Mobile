package com.bot4vps.mobile.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.bot4vps.mobile.ui.components.*
import com.bot4vps.mobile.ui.theme.OutlineVariant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.hilt.navigation.compose.hiltViewModel
import com.bot4vps.mobile.ui.theme.Background
import com.bot4vps.mobile.ui.theme.CardBackground
import com.bot4vps.mobile.ui.theme.OnSurface
import com.bot4vps.mobile.ui.theme.OnSurfaceVariant
import com.bot4vps.mobile.ui.theme.Outline
import com.bot4vps.mobile.ui.theme.Primary
import com.bot4vps.mobile.ui.theme.TextDim
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SetupUrlScreen(
    onUrlSaved: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(value: String = url) {
        if (saving) return
        if (value.isBlank()) { error = "Введите полный код привязки из Web-панели"; return }
        saving = true
        scope.launch {
            try {
                if (value.trim().startsWith("b4vps:")) {
                    viewModel.pairFromWeb(value.trim())
                    url = ""; paired = true
                    delay(1200)
                } else error("Нужен полный код b4vps:… из Web-панели")
                onUrlSaved()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Некорректный адрес панели" }
            finally { saving = false }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBackground, RoundedCornerShape(12.dp))
                .border(1.dp, OutlineVariant, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Bot4VPS",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Text(
                text = "Подключение телефона",
                fontSize = 14.sp,
                color = TextDim
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Создайте привязку в Web-панели: Настройки → Мобильное приложение. Отсканируйте QR или введите код.",
                fontSize = 13.sp,
                color = OnSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            QrScanButton(enabled = !saving, onCode = { save(it) }, onError = { error = it })
            TextButton(enabled = !saving, onClick = { manualCode = true }) { Text("Ввести код") }
            if (manualCode) PanelTextField(
                value = url,
                enabled = !saving,
                onValueChange = {
                    url = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("b4vps:…", color = TextDim) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { save() }
                ),
                shape = RoundedCornerShape(8.dp)
            )

            if (error != null) {
                Text(text = error!!, color = Color(0xFFEF4444), fontSize = 13.sp)
            }
            if (paired) Text("Телефон успешно привязан", color = OnSurfaceVariant)

            if (manualCode) PanelButton(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Продолжить", fontWeight = FontWeight.SemiBold)
            }

            Text(
                text = "В локальной сети домен и белый IP не нужны. Защищённое подключение API настраивается в Web-панели.",
                fontSize = 12.sp,
                color = TextDim,
                lineHeight = 16.sp
            )
        }
    }
}
