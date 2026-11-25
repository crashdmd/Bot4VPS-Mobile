package com.bot4vps.mobile.ui.browser

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class ServiceBrowserViewModel @Inject constructor(val panel: NativePanelClient, private val prefs: PreferencesManager) : ViewModel() {
    internal var proxy by mutableStateOf<ServiceBrowserProxy?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var waiting by mutableStateOf<Int?>(null); private set
    private var job: Job? = null
    private var origin: String? = null
    private var control: ServiceBrowserControl? = null
    private var sessionId: String? = null
    private var generation = 0
    fun open(node: String, service: String) {
        if (job?.isActive == true || proxy != null) return
        val attempt = ++generation
        job = viewModelScope.launch {
            error = null; waiting = null
            try {
                val current = prefs.baseUrl.first() ?: error("Укажите адрес панели")
                origin = current
                val data = panel.request("POST", "/api/service-browser/${encodeSegment(node)}/${encodeSegment(service)}/open", body = buildJsonObject { put("protocol", 2) }, baseUrl = current).jsonObject
                require(prefs.baseUrl.first() == current) { "Подключение к панели изменилось" }
                val path = data["socket_path"]?.jsonPrimitive?.content ?: error("Панель не вернула подключение сервиса")
                require(path.startsWith("/api/service-browser/") && !path.contains('?'))
                val controlPath = data["control_path"]?.jsonPrimitive?.content ?: error("Обновите API панели для безопасного открытия сервиса")
                require(controlPath.startsWith("/api/service-browser/") && !controlPath.contains('?'))
                val target = BrowserTarget(data.getValue("session_id").jsonPrimitive.content,
                    data.getValue("origin").jsonPrimitive.content, data.getValue("entry_path").jsonPrimitive.content,
                    PanelUrl.endpoint(current, path), service)
                require(target.session.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Панель вернула неверную сессию сервиса" }
                require(path == "/api/service-browser/${target.session}/stream" && controlPath == "/api/service-browser/${target.session}/control")
                sessionId = target.session
                require(target.entry.startsWith('/') && !target.entry.startsWith("//") && '\r' !in target.entry && '\n' !in target.entry)
                val endpoint = PanelUrl.endpoint(current, controlPath)
                fun finish(message: String) { viewModelScope.launch { if (generation == attempt) { stop(); error = message } } }
                val lifetime = ServiceBrowserControl(panel.httpClient(endpoint), endpoint,
                    token = { panel.renewSocketSession(endpoint) },
                    changed = { ready, seconds -> viewModelScope.launch { if (generation == attempt) waiting = if (ready) null else seconds } },
                    ended = ::finish)
                control = lifetime
                withContext(Dispatchers.IO) { lifetime.awaitGateway() }
                // Allocate and assign together on Main, so cancellation cannot
                // lose ownership of a newly created loopback listener.
                proxy = ServiceBrowserProxy(target, awaitGateway = lifetime::awaitGateway,
                    logout = { finish("Вы вышли из сервиса. Туннель закрыт.") },
                    timedOut = { finish("Шлюз не ответил в течение 60 секунд. Туннель завершён. Проверьте результат последнего действия после восстановления связи.") }) { scope ->
                    ServiceTunnelSocket(panel.httpClient(target.endpoint), target.endpoint, scope) { panel.renewSocketSession(target.endpoint) }
                }
                prefs.baseUrl.collect { value ->
                    if (value != origin) { stop(); error = "Подключение к панели изменилось. Откройте сервис заново." }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { stop(); error = errorText(e) }
        }
    }
    fun stop() {
        val previous = proxy
        val id = sessionId; sessionId = null
        generation++; waiting = null
        proxy = null; job?.cancel(); job = null
        previous?.close(); control?.close(); control = null
        val address = origin
        if (id != null) viewModelScope.launch { runCatching { panel.request("DELETE", "/api/service-browser/$id", baseUrl = address) } }
    }
    override fun onCleared() { proxy?.close(); proxy = null; control?.close(); control = null; super.onCleared() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceBrowserDialog(nodeId: String, serviceId: String, onClose: () -> Unit, vm: ServiceBrowserViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val title = when (serviceId) { "3x-ui" -> "3x-ui"; "adguardhome" -> "AdGuard Home"; "ssclash" -> "SSClash-Go"; "podkop" -> "Podkop"; else -> "LuCI" }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failedGet by remember { mutableStateOf(false) }
    var wasWaiting by remember { mutableStateOf(false) }
    var authRequest by remember { mutableStateOf<HttpAuthHandler?>(null) }
    var authRealm by remember { mutableStateOf("") }
    val proxy = vm.proxy
    fun release(view: WebView) {
        if (browser !== view) return
        browser = null
        authRequest?.cancel(); authRequest = null
        view.stopLoading(); view.clearHistory(); view.clearCache(true); view.destroy()
        CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
    }
    LaunchedEffect(nodeId, serviceId) {
        // The embedded browser has its own cookie store. Native panel sessions
        // do not use it; service cookies are erased between service origins.
        CookieManager.getInstance().removeAllCookies { ready = true }
        WebStorage.getInstance().deleteAllData()
        vm.open(nodeId, serviceId)
    }
    DisposableEffect(vm) { onDispose {
        browser?.let(::release)
        authRequest?.cancel(); authRequest = null
        vm.stop(); CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    } }
    LaunchedEffect(vm.waiting) {
        if (vm.waiting != null) wasWaiting = true
        else if (wasWaiting && failedGet && vm.error == null) { wasWaiting = false; failedGet = false; webError = null; browser?.reload() }
    }
    fun reopen() {
        ready = false; webError = null; failedGet = false
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { ready = true; vm.open(nodeId, serviceId) }
    }
    fun back() { if (browser?.canGoBack() == true) browser?.goBack() else onClose() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        BackHandler(onBack = ::back)
        Scaffold(modifier = Modifier.fillMaxSize(), contentWindowInsets = WindowInsets.safeDrawing, topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { if (vm.proxy == null) reopen() else { webError = null; browser?.reload() } }) { Icon(Icons.Default.Refresh, "Обновить") }
                    TextButton(onClick = onClose) { Text("Закрыть") }
                })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (loading || proxy == null && vm.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                (vm.error ?: webError)?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                vm.waiting?.let { Text("Нет ответа от шлюза. Ждём восстановления связи… $it с", Modifier.padding(12.dp)) }
                if (proxy == null && vm.error != null) TextButton(onClick = ::reopen, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Открыть заново") }
                if (proxy != null && ready) AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), onReset = null, onRelease = ::release, factory = { ctx ->
                    WebView(ctx).apply {
                        browser = this
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        fun syncViewport() {
                            if (url?.startsWith(proxy.origin + "/") != true) return
                            serviceViewportScript(serviceId, width, height)?.let { evaluateJavascript(it, null) }
                        }
                        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) syncViewport()
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.toString().startsWith(proxy.origin + "/")) return false
                                if (request.hasGesture() && request.isForMainFrame && request.url.scheme in listOf("https", "http"))
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                return true
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.toString().startsWith(proxy.origin + "/")) return null
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "Ресурс за пределами выбранного сервиса".byteInputStream())
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
                            override fun onPageCommitVisible(view: WebView, url: String?) { syncViewport() }
                            override fun onPageFinished(view: WebView, url: String?) { loading = false; syncViewport() }
                            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) { syncViewport() }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { loading = false; failedGet = request.method == "GET"; webError = "Не удалось загрузить сервис через панель" }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode >= 500) {
                                    loading = false; failedGet = request.method == "GET"
                                    webError = if (failedGet) "Шлюз временно недоступен. Ждём восстановления связи." else "Связь прервалась. Результат действия пока неизвестен; проверьте его после восстановления связи."
                                }
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
                            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                                // The regular HTML login is handled by the service. HTTP
                                // Basic auth is requested using the native prompt below.
                                authRequest = handler; authRealm = realm.orEmpty()
                            }
                        }
                        loadUrl(proxy.startUrl)
                    }
                })
            }
        }
        BasicAuthPrompt(authRequest, authRealm) { authRequest = null }
    }
}

@Composable
private fun BasicAuthPrompt(handler: HttpAuthHandler?, authRealm: String, dismiss: () -> Unit) {
    val request = handler ?: return
    var username by remember(request) { mutableStateOf("") }
    var password by remember(request) { mutableStateOf("") }
    com.bot4vps.mobile.ui.components.PanelAlertDialog(onDismissRequest = { request.cancel(); dismiss() }, title = { Text("Вход в сервис") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (authRealm.isNotBlank()) Text(authRealm)
            OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        }
    }, confirmButton = { TextButton(onClick = { request.proceed(username, password); dismiss() }) { Text("Войти") } },
        dismissButton = { TextButton(onClick = { request.cancel(); dismiss() }) { Text("Отмена") } })
}
