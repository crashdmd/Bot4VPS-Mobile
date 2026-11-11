package com.bot4vps.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bot4vps.mobile.data.api.NativePanelClient
import com.bot4vps.mobile.data.local.AppLockManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.auth.PinEntryScreen
import com.bot4vps.mobile.data.repository.checkedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun MasterKeyViewDialog(client: NativePanelClient, protection: AppLockManager, onClose: () -> Unit) {
    val viewer = remember(client) { MasterKeyViewer({ path, body -> client.checkedObject("POST", path, body) }, initialStep = MasterKeyStep.LOADING) }
    val state by viewer.state.collectAsState()
    val lock by protection.state.collectAsState()
    var origin by remember { mutableStateOf("") }
    var operationKey by remember { mutableStateOf<SensitiveOperationKey?>(null) }
    var deviceOperationPublicKey by remember { mutableStateOf<String?>(null) }
    var usePin by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val close by rememberUpdatedState(onClose)
    // Keep the same state object for the lifecycle observer across all steps.
    var value by remember { mutableStateOf("") }
    var copied by remember(state.step) { mutableStateOf(false) }
    fun clearClipboard() = client.masterKeyClipboard.clear()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.step) { value = "" }
    LaunchedEffect(state.error) { if (state.error != null) value = "" }
    DisposableEffect(viewer, protection, lifecycle) {
        protection.protectScreen()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { value = ""; if (viewer.background()) close() }
            if (event == Lifecycle.Event.ON_START) { viewer.foreground(); client.masterKeyClipboard.clearExpired() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewer.close(); operationKey = null; value = ""; protection.releaseScreen() }
    }
    LaunchedEffect(state.resendAt) {
        do { now = System.currentTimeMillis(); if (state.resendAt > now) delay(500) } while (state.resendAt > now)
    }
    LaunchedEffect(lock.retryAt) {
        do { now = System.currentTimeMillis(); if (lock.retryAt > now) delay(250) } while (lock.retryAt > now)
    }
    LaunchedEffect(viewer) {
        viewer.prepare(MasterKeyStep.LOADING)
        try {
            val url = client.url("/api/masterkey/phone/status")
            origin = "${url.scheme}://${url.host}:${url.port}"
            val status = client.checkedObject("POST", "/api/masterkey/phone/view/availability", kotlinx.serialization.json.buildJsonObject {
                put("protected", kotlinx.serialization.json.JsonPrimitive(lock.enabled))
            })
            val channels = (status["channels"] as? JsonObject)?.flag("any") == true
            var registered = false
            if (lock.enabled) {
                try {
                    deviceOperationPublicKey = client.masterKeyOperationPublicKey()
                    operationKey = protection.sensitiveSeedId?.let { id -> withContext(Dispatchers.IO) { client.sensitiveKeys.read(origin, id) } }
                    registered = operationKey != null && operationKey?.publicKey == status.text("public_key") &&
                        deviceOperationPublicKey == status.text("device_operation_public_key")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    viewer.blocked(if (channels)
                        "Защищённый ключ телефона недоступен. Посмотрите ключ в Web-панели с паролем администратора и вторым фактором либо через CLI: bot4vps → Безопасность → Мастер-ключ."
                    else "Защищённый ключ телефона недоступен и второй фактор не настроен. Перепривяжите телефон, настройте 2FA или Telegram в Web-панели либо посмотрите ключ через CLI: bot4vps → Безопасность → Мастер-ключ.")
                    return@LaunchedEffect
                }
            }
            val step = masterKeyInitialStep(lock.enabled, registered, channels)
            if (step == MasterKeyStep.BLOCKED) viewer.blocked(masterKeyUnavailableMessage()) else viewer.prepare(step)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { viewer.blocked(e.message ?: "Не удалось проверить защищённый просмотр. Проверьте подключение и повторите попытку.") }
    }
    LaunchedEffect(state.expiresAt) {
        if (state.expiresAt > 0) {
            delay((state.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            viewer.expire(); clearClipboard(); close()
        }
    }
    fun submit(localPin: String? = null, biometric: Boolean = false) {
        if (state.busy || submitting || lock.busy) return
        val input = localPin ?: value; value = ""
        submitting = true
        scope.launch {
            try {
            when (state.step) {
                MasterKeyStep.PASSWORD -> viewer.begin(input)
                MasterKeyStep.CODE -> viewer.confirm(input)
                MasterKeyStep.SETUP_PIN, MasterKeyStep.LOCAL -> viewer.authenticateLocal(
                    authorize = { action -> protection.sensitiveOperation(if (biometric) null else input, action) },
                    perform = { seed ->
                    var key = operationKey
                    if (viewer.state.value.step == MasterKeyStep.SETUP_PIN) {
                        val id = protection.sensitiveSeedId ?: error("Ключ защиты недоступен")
                        val created = withContext(Dispatchers.IO) { client.sensitiveKeys.create(origin, id, seed) }
                        key = created; operationKey = created
                        val nativePublicKey = deviceOperationPublicKey ?: error("Ключ Keystore недоступен")
                        viewer.enroll(created.publicKey, nativePublicKey,
                            signDevice = { challenge -> client.signMasterKeyEnrollment(challenge, created.publicKey) },
                            signOperation = { challenge -> withContext(Dispatchers.Default) {
                                created.signEnrollment(seed, origin, challenge.text("device_id") ?: error("Нет устройства"),
                                    challenge.text("challenge_id") ?: error("Нет подтверждения"), challenge.text("nonce") ?: error("Нет подтверждения"), nativePublicKey)
                            } }, signKeystore = { challenge -> client.signMasterKeyOperation(challenge, created.publicKey) })
                    }
                    if (viewer.state.value.step == MasterKeyStep.LOCAL) {
                        val signingKey = key ?: error("Настройте защищённый просмотр заново")
                        viewer.local(signKeystore = { client.signMasterKeyOperation(it) }) { challenge -> withContext(Dispatchers.Default) {
                            signingKey.sign(seed, origin, challenge.text("device_id") ?: error("Нет устройства"),
                                challenge.text("challenge_id") ?: error("Нет подтверждения"), challenge.text("nonce") ?: error("Нет подтверждения"))
                        } }
                    }
                })
                else -> Unit
            }
            } finally {
                submitting = false
                if (viewer.state.value.step in setOf(MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN) && (biometric || viewer.state.value.error != null)) usePin = true
            }
        }
    }
    fun dismiss() { viewer.close(); clearClipboard(); onClose() }
    LaunchedEffect(state.step, usePin, lock.foreground, submitting) {
        if (state.step in setOf(MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN) && lock.foreground && lock.biometricEnabled && !usePin && state.error == null && !submitting)
            submit(biometric = true)
    }
    if (state.step in setOf(MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN)) {
        val remaining = (lock.retryAt - now).coerceIn(0, 300_000)
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
            PinEntryScreen(title = "Подтвердите личность", prompt = "Введите PIN-код Bot4VPS", digits = value.length,
                onDigit = { digit ->
                    if (!state.busy && !submitting && !lock.busy && remaining == 0L && value.length < LockPin.LENGTH) {
                        protection.clearError(); viewer.clearError(); value += digit
                        if (value.length == LockPin.LENGTH) submit(localPin = value)
                    }
                }, onDelete = { value = value.dropLast(1); protection.clearError() }, onBack = ::dismiss,
                enabled = !state.busy && !submitting && !lock.busy && remaining == 0L,
                busy = state.busy || submitting || lock.busy,
                error = if (remaining > 0) "Повторный ввод через ${(remaining + 999) / 1000} с" else state.error?.takeIf(String::isNotBlank),
                onBiometric = if (lock.biometricEnabled) ({ usePin = false; submit(biometric = true) }) else null)
        }
        return
    }
    AlertDialog(onDismissRequest = ::dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(when (state.step) { MasterKeyStep.BLOCKED -> "Просмотр пока недоступен"; MasterKeyStep.LOADING, MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN, MasterKeyStep.PASSWORD -> "Просмотр мастер-ключа"; MasterKeyStep.CODE -> "Подтверждение владельца"; MasterKeyStep.READY -> "Личность подтверждена"; MasterKeyStep.KEY -> "Мастер-ключ" }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.step) {
                MasterKeyStep.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                MasterKeyStep.BLOCKED -> Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN -> Unit
                MasterKeyStep.READY -> Text("Можно посмотреть мастер-ключ или отменить операцию.")
                MasterKeyStep.PASSWORD -> {
                    Text("Введите пароль учётной записи панели Bot4VPS.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Пароль панели") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !state.busy && !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
                }
                MasterKeyStep.CODE -> {
                    Text(if (state.channel == "totp") "Введите код из приложения-аутентификатора." else "Код отправлен в Telegram. Введите его для просмотра ключа.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value, { value = it.filter { char -> char in '0'..'9' }.take(6) }, Modifier.fillMaxWidth(), label = { Text("Код подтверждения") }, singleLine = true, enabled = !state.busy && !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
                    if (state.channel == "telegram") {
                        val seconds = ((state.resendAt - now).coerceAtLeast(0) + 999) / 1000
                        TextButton(onClick = { scope.launch { viewer.resend() } }, enabled = !state.busy && seconds == 0L) { Text(if (seconds == 0L) "Отправить код ещё раз" else "Повторная отправка через $seconds с") }
                    }
                    if (state.error?.let { it.contains("начните заново", true) || it.contains("истёк", true) } == true) TextButton(onClick = { viewer.close() }, enabled = !state.busy) { Text("Начать заново") }
                }
                MasterKeyStep.KEY -> {
                    Text("Ключ автоматически скроется через 30 секунд. Сохраните ключ в надёжном месте. Он нужен для восстановления доступа к зашифрованным данным панели.", style = MaterialTheme.typography.bodySmall)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(state.key.orEmpty(), Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace)
                    }
                    OutlinedButton(onClick = {
                        client.masterKeyClipboard.copy(state.key.orEmpty(), state.expiresAt)
                        copied = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Ключ скопирован" else "Скопировать ключ") }
                }
            }
            if (state.step != MasterKeyStep.BLOCKED) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = {
            if (state.step in setOf(MasterKeyStep.KEY, MasterKeyStep.BLOCKED)) TextButton(onClick = ::dismiss) { Text("Закрыть") }
            else if (state.step == MasterKeyStep.READY) TextButton(onClick = viewer::approve) { Text("Посмотреть мастер-ключ") }
            else if (state.step != MasterKeyStep.LOADING) TextButton(onClick = { submit() },
                enabled = !state.busy && !lock.busy && !submitting && when (state.step) {
                    MasterKeyStep.PASSWORD -> value.isNotEmpty()
                    MasterKeyStep.CODE -> value.length == 6
                    MasterKeyStep.SETUP_PIN, MasterKeyStep.LOCAL -> (lock.biometricEnabled && !usePin) || value.length == 4
                    else -> false
                }) { Text(when (state.step) { MasterKeyStep.LOCAL, MasterKeyStep.SETUP_PIN -> "Показать ключ"; MasterKeyStep.PASSWORD -> "Продолжить"; else -> "Подтвердить" }) }
        }, dismissButton = { if (state.step !in setOf(MasterKeyStep.KEY, MasterKeyStep.BLOCKED)) TextButton(onClick = ::dismiss) { Text("Отмена") } })
}
