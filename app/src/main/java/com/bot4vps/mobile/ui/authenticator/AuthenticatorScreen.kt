package com.bot4vps.mobile.ui.authenticator

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import com.bot4vps.mobile.ui.auth.rememberQrScanner
import com.bot4vps.mobile.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class AuthenticatorViewModel @Inject constructor(val storage: AuthenticatorStorage, val security: SecurityRepository, val appLock: AppLockManager, private val prefs: PreferencesManager, private val registration: PanelTotpRepository) : ViewModel() {
    val profiles = prefs.profiles
    val baseUrl = prefs.baseUrl
    var accounts by mutableStateOf<List<AuthenticatorAccount>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<AuthenticatorAccount?>(null); private set
    fun message(value: String) { error = value }
    fun dismissError() { error = null }
    fun dismissPending() { pending = null }
    fun load() = action { accounts = storage.accounts() }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    fun add(account: AuthenticatorAccount) = action { storage.add(account); accounts = storage.accounts() }
    fun remove(account: AuthenticatorAccount) = action { storage.remove(account.id); accounts = storage.accounts() }
    fun resume(account: AuthenticatorAccount) { pending = account }
    fun confirmPanel() {
        val account = pending ?: return
        action {
            registration.confirm(account); accounts = storage.accounts(); pending = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorScreen(onBack: () -> Unit, offline: Boolean = false, onConnect: (() -> Unit)? = null, viewModel: AuthenticatorViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by viewModel.security.state.collectAsState()
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val current by viewModel.baseUrl.collectAsState(initial = null)
    DisposableEffect(viewModel) { viewModel.appLock.protectScreen(); onDispose { viewModel.appLock.releaseScreen() } }
    LaunchedEffect(lifecycle, offline) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { viewModel.storage.revision.collect { viewModel.load() } }
        if (!offline) viewModel.security.poll(5000)
    } }
    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = System.currentTimeMillis() / 1000.0; delay(100) } } }
    var draft by remember { mutableStateOf<AuthenticatorAccount?>(null) }
    var remove by remember { mutableStateOf<AuthenticatorAccount?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val filtered = viewModel.accounts.filter { it.name.contains(search.trim(), ignoreCase = true) || it.issuer.contains(search.trim(), ignoreCase = true) }
    val scanner = rememberQrScanner("Наведите камеру на QR-код Authenticator", { code ->
        runCatching { readTotpQr(code) }.onSuccess { draft = it }.onFailure { viewModel.message(errorText(it)) }
    }, viewModel::message)
    viewModel.error?.let { PanelAlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Authenticator") }, text = { Text(it) }, confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("Ок") } }) }
    viewModel.pending?.let { account -> PanelAlertDialog(onDismissRequest = viewModel::dismissPending,
        title = { Text("Завершить включение 2FA?") },
        text = { Text("Приложение подтвердит первый код на ${account.panelOrigin} и включит двухфакторную аутентификацию. Код сохранится на телефоне.") },
        confirmButton = { TextButton(enabled = !viewModel.busy, onClick = viewModel::confirmPanel) { Text("Включить 2FA") } },
        dismissButton = { TextButton(enabled = !viewModel.busy, onClick = viewModel::dismissPending) { Text("Закрыть") } }) }
    remove?.let { account -> PanelAlertDialog(onDismissRequest = { remove = null }, title = { Text("Удалить код с телефона?") },
        text = { Text("${account.issuer} · ${account.name}\nНастройка 2FA на сервисе сохранится. Убедитесь, что у вас есть другой способ входа.") },
        confirmButton = { TextButton(onClick = { remove = null; viewModel.remove(account) }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Отмена") } }) }
    draft?.let { account -> TotpAccountDialog(account, onDismiss = { draft = null }, onSave = { viewModel.add(it); draft = null }) }
    if (showAddMenu) com.bot4vps.mobile.ui.components.PanelModalBottomSheet(onDismissRequest = { showAddMenu = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
        scrimColor = Color.Black.copy(alpha = .55f)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionRow("Сканировать QR-код", "", Icons.Default.QrCodeScanner, { showAddMenu = false; scanner() }, enabled = !viewModel.busy)
            ActionRow("Ввести ключ настройки", "", Icons.Default.Keyboard, { showAddMenu = false; draft = AuthenticatorAccount(name = "", secret = "") }, enabled = !viewModel.busy)
        }
    }
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Authenticator") }, navigationIcon = { if (!offline) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { if (!viewModel.busy) showAddMenu = true }) { Icon(Icons.Default.Add, "Добавить код 2FA") } },
        bottomBar = {
        if (offline && onConnect != null) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                PanelSecondaryButton(enabled = !viewModel.busy, onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Подключиться к сервису") }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PanelSearchField(search, { search = it }, Modifier.fillMaxWidth(), "Поиск по сервису или аккаунту") }
            if (offline) item { PanelCard {
                Text(if (profiles.any { it.url == current?.trimEnd('/') && it.revoked }) "Доступ этого телефона к панели отозван. Коды Authenticator сохранены." else "Authenticator работает без подключения к панели.")
            } }
            if (viewModel.accounts.isEmpty() && !viewModel.busy) item { PanelCard { Text("Добавьте первый аккаунт Authenticator") } }
            else if (filtered.isEmpty() && !viewModel.busy) item { Text("Коды не найдены") }
            items(filtered, key = { it.id }) { account -> PanelCard {
                Row { Column(Modifier.weight(1f)) { Text(account.issuer.ifBlank { "TOTP" }, style = MaterialTheme.typography.labelMedium); Text(account.name) }
                    IconButton(enabled = !viewModel.busy, onClick = { remove = account }) { Icon(Icons.Default.DeleteOutline, "Удалить локальный код") } }
                val mismatch = account.panelOrigin == snapshot.origin && snapshot.supported == true && account.generation != null && account.generation != snapshot.totpGeneration
                if (!account.active) { Text("Включение 2FA ещё не завершено"); TextButton(enabled = !viewModel.busy, onClick = { viewModel.resume(account) }) { Text("Завершить включение 2FA") } }
                else if (mismatch) Text("TOTP на панели заменён или сброшен. Этот код больше не подходит.", color = MaterialTheme.colorScheme.error)
                else {
                    val codeTime = if (account.panelOrigin == snapshot.origin && snapshot.supported == true && snapshot.error == null) snapshot.now() else now
                    val code = runCatching { totpCode(account, codeTime.toLong()) }.getOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code ?: "Секрет повреждён", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        if (code != null) key(codeTime.toLong() / account.period) {
                            val remaining = account.period - codeTime % account.period
                            val progress by animateFloatAsState((remaining / account.period).toFloat(), tween(100, easing = LinearEasing), label = "Обновление TOTP")
                            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp).semantics { stateDescription = "До обновления кода ${kotlin.math.ceil(remaining).toInt()} секунд" }, strokeWidth = 3.dp)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
internal fun TotpAccountDialog(account: AuthenticatorAccount, onDismiss: () -> Unit, onSave: (AuthenticatorAccount) -> Unit) {
    var name by remember { mutableStateOf(account.name) }; var issuer by remember { mutableStateOf(account.issuer) }; var secret by remember { mutableStateOf(account.secret) }
    var error by remember { mutableStateOf<String?>(null) }
    PanelAlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить аккаунт TOTP") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelTextField(issuer, { issuer = it }, label = { Text("Сервис") }, singleLine = true)
            PanelTextField(name, { name = it }, label = { Text("Аккаунт") }, singleLine = true)
            PanelTextField(secret, { secret = it }, label = { Text("Ключ настройки") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("${account.algorithm} · ${account.digits} цифр · ${account.period} с", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { validateTotp(account.copy(name = name.trim(), issuer = issuer.trim(), secret = secret.trim())) }.onSuccess(onSave).onFailure { error = errorText(it) } }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
