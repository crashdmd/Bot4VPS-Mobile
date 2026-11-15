package com.bot4vps.mobile

import com.bot4vps.mobile.ui.components.PanelAlertDialog

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import com.bot4vps.mobile.notifications.PhoneNotificationService
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bot4vps.mobile.ui.terminal.TerminalSessionsViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.bot4vps.mobile.ui.navigation.Bot4VpsNavHost
import com.bot4vps.mobile.ui.components.PanelBackground
import com.bot4vps.mobile.ui.theme.Background
import com.bot4vps.mobile.ui.theme.Bot4VpsTheme
import dagger.hilt.android.AndroidEntryPoint
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.local.AppLockManager
import com.bot4vps.mobile.data.local.MobileUpdateManager
import com.bot4vps.mobile.ui.settings.MobileUpdateDialog
import androidx.compose.runtime.LaunchedEffect
import com.bot4vps.mobile.ui.auth.AppLockScreen
import com.bot4vps.mobile.ui.auth.BiometricPromptHost
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var authRepository: com.bot4vps.mobile.data.repository.AuthRepository
    @Inject lateinit var accessStatus: com.bot4vps.mobile.data.api.MobileAccessStatus
    @Inject lateinit var securityRepository: com.bot4vps.mobile.data.repository.SecurityRepository
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var appLock: AppLockManager
    @Inject lateinit var mobileUpdates: MobileUpdateManager
    @Inject lateinit var masterKeyClipboard: com.bot4vps.mobile.data.local.MasterKeyClipboard
    private val terminalSessions: TerminalSessionsViewModel by viewModels()
    private val pendingSecurity = MutableStateFlow<String?>(null)
    private val pendingSecurityOrigin = MutableStateFlow<String?>(null)
    private val pendingEvent = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        pendingEvent.value = intent.getStringExtra("event_id")
        pendingSecurity.value = intent.getStringExtra("security_id")
        pendingSecurityOrigin.value = intent.getStringExtra("security_origin")
        lifecycleScope.launch { accessStatus.revoked.filterNotNull().collect { origin ->
            if (com.bot4vps.mobile.data.model.panelProfileOrigin(authRepository.baseUrl.first()) == origin) terminalSessions.closeAll()
            try { authRepository.recordRevocation(origin) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Keep the explicit revocation message even if local cleanup needs retry. */ }
        } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { preferences.username.collectLatest { user -> if (user != null) securityRepository.poll(5000) } }
            launch {
                try { authRepository.refreshProfiles() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { }
                securityRepository.pollAll(5000)
            }
        } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferences.profiles.map { profiles -> profiles.any { !it.revoked && !it.localDisconnected } }
                    .distinctUntilChanged().collect { enabled ->
                        if (enabled) PhoneNotificationService.start(this@MainActivity)
                        else PhoneNotificationService.stop(this@MainActivity)
                    }
            }
        }
        enableEdgeToEdge()
        setContent {
            val protection by appLock.state.collectAsState()
            val secureViews by appLock.secureViews.collectAsState()
            val theme by preferences.theme.collectAsState(initial = "dark")
            LaunchedEffect(protection.allowsContent, protection.foreground) {
                if (protection.allowsContent && protection.foreground) mobileUpdates.check(manual = false)
            }
            SideEffect {
                if (protection.protectsContent || secureViews > 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = theme == "light"
                bars.isAppearanceLightNavigationBars = theme == "light"
            }
            Bot4VpsTheme(darkTheme = theme != "light", blueTheme = theme == "glass") {
                BiometricPromptHost(this@MainActivity, appLock)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    val eventId by pendingEvent.collectAsState()
                    val securityId by pendingSecurity.collectAsState()
                    val securityOrigin by pendingSecurityOrigin.collectAsState()
                    val revokedOrigin by accessStatus.revoked.collectAsState()
                    val connectionChange by authRepository.connectionNotice.collectAsState()
                    val connections by preferences.profiles.collectAsState(initial = emptyList())
                    val migrationNotice by authRepository.migrationNotice.collectAsState()
                    val connectionScope = androidx.compose.runtime.rememberCoroutineScope()
                    var choosingConnection by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var connectionError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    val contentState = rememberSaveableStateHolder()
                    if (protection.allowsContent) contentState.SaveableStateProvider("panel") {
                        com.bot4vps.mobile.ui.security.SecurityAlertsHost(securityId, securityOrigin, {
                            pendingSecurity.value = null; pendingSecurityOrigin.value = null; intent.removeExtra("security_id"); intent.removeExtra("security_origin")
                        }) {
                        PanelBackground { Bot4VpsNavHost(terminalSessions = terminalSessions, eventId = eventId, onEventConsumed = { pendingEvent.value = null; intent.removeExtra("event_id"); intent.data = null }, revokedOrigin = revokedOrigin, connectionChange = connectionChange) }
                        if (connectionChange == null) revokedOrigin?.let { origin -> PanelAlertDialog(
                            onDismissRequest = accessStatus::clear,
                            title = { androidx.compose.material3.Text("Доступ телефона отозван") },
                            text = { androidx.compose.material3.Text("В панели $origin отозвали доступ этого телефона. Коды Authenticator сохранены и работают без панели.") },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = accessStatus::clear) { androidx.compose.material3.Text("Ок") } }) }
                        connectionChange?.let { change -> PanelAlertDialog(
                            onDismissRequest = { if (!choosingConnection) { authRepository.dismissConnectionNotice(); accessStatus.clear() } },
                            title = { androidx.compose.material3.Text(if (change.revoked) "Доступ телефона отозван" else "Телефон отключён") },
                            text = { androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.Text("Устройство отвязано от сервиса ${change.origin}.\n" +
                                    if (change.choicesRequired) "Выберите панель для переключения. Коды Authenticator сохранены."
                                    else if (change.switched && change.nextOrigin != null) "Приложение переключено на панель ${change.nextOrigin}. Коды Authenticator сохранены."
                                    else if (change.switched) "Действующих подключений больше нет. Открыт Authenticator; коды сохранены."
                                    else "Текущая панель и коды Authenticator сохранены.")
                                change.roleMessage?.let { androidx.compose.material3.Text(it) }
                                if (change.choicesRequired) connections.filter { !it.revoked && !it.localDisconnected }.forEach { profile ->
                                    androidx.compose.material3.TextButton(enabled = !choosingConnection, onClick = {
                                        connectionScope.launch {
                                            choosingConnection = true; connectionError = null
                                            try { authRepository.selectProfile(profile.url); accessStatus.clear() }
                                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (e: Exception) { connectionError = com.bot4vps.mobile.data.api.errorText(e) }
                                            finally { choosingConnection = false }
                                        }
                                    }) { androidx.compose.material3.Text("${profile.name}\n${profile.url}") }
                                }
                                connectionError?.let { androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                            } },
                            confirmButton = { androidx.compose.material3.TextButton(enabled = !choosingConnection, onClick = { authRepository.dismissConnectionNotice(); accessStatus.clear() }) { androidx.compose.material3.Text("Ок") } }) }
                        migrationNotice?.let { message -> PanelAlertDialog(onDismissRequest = authRepository::dismissMigrationNotice,
                            title = { androidx.compose.material3.Text("Адрес сервиса изменён") }, text = { androidx.compose.material3.Text(message) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = authRepository::dismissMigrationNotice) { androidx.compose.material3.Text("Ок") } }) }
                        if (revokedOrigin == null) com.bot4vps.mobile.ui.authenticator.PairingTotpPrompt()
                        MobileUpdateDialog(mobileUpdates)
                        }
                    } else AppLockScreen(appLock, onClose = { finish() })
                }
            }
        }
    }
    override fun onStart() { super.onStart(); masterKeyClipboard.clearExpired(); appLock.foreground() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) masterKeyClipboard.clearExpired()
    }
    override fun onStop() {
        if (!isChangingConfigurations) {
            appLock.background()
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            window.decorView.clearFocus()
        }
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEvent.value = intent.getStringExtra("event_id")
        pendingSecurity.value = intent.getStringExtra("security_id")
        pendingSecurityOrigin.value = intent.getStringExtra("security_origin")
    }
}
