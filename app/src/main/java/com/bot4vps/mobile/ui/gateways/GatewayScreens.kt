package com.bot4vps.mobile.ui.gateways

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Instant

@Composable
private fun ObserveGateways(vm: GatewayViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.observe() } }
}

@Composable
fun DashboardGateways(inventory: GatewayInventory, onGateway: (String) -> Unit, onAll: () -> Unit) {
    val nodes = inventory.gateways
    var index by remember(nodes.map { it.id }) { mutableIntStateOf(0) }
    LaunchedEffect(nodes.map { it.id }) { while (nodes.size > 1) { delay(5000); index = (index + 1) % nodes.size } }
    PanelCard("Шлюзы") {
        Text("${nodes.count { it.online }} в сети · ${nodes.count { !it.online }} требуют внимания · ${nodes.size} всего", style = MaterialTheme.typography.bodyMedium)
        inventory.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (nodes.isEmpty()) Text("Нет шлюзов", color = MaterialTheme.colorScheme.onSurfaceVariant)
        nodes.getOrNull(index)?.let { node -> GatewayCard(node) { onGateway(node.id) } }
        TextButton(onClick = onAll, modifier = Modifier.fillMaxWidth()) { Text("Все шлюзы →") }
    }
}

@Composable
private fun GatewayCard(node: Gateway, onClick: () -> Unit) {
    PanelSurfaceCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary)
                Text(node.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusBadge(if (node.online) "В сети" else if (node.monitor.flag("stale")) "Нет свежих данных" else "Нет связи", if (node.online) StatusTone.GOOD else StatusTone.WARNING, maxLines = 1)
            }
            Text(node.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("CPU", percent(node.monitor.number("cpu_pct")), Modifier.weight(1f), node.monitor.number("cpu_pct"))
                StatTile("Память", percent(node.monitor.number("ram_pct")), Modifier.weight(1f), node.monitor.number("ram_pct"))
            }
            node.monitor.text("error")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun GatewaysScreen(onGateway: (String) -> Unit, onAdd: () -> Unit, vm: GatewayViewModel = hiltViewModel()) {
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    ObserveGateways(vm)
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Шлюзы") }, actions = { IconButton(onClick = onAdd, enabled = inventory.enabled) { Icon(Icons.Default.Add, "Добавить шлюз") } }) }) { padding ->
        Refreshable(vm.busy, vm::refresh, Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = refreshArrangement(12.dp)) {
                item { OutlinedTextField(query, { query = it }, label = { Text("Поиск шлюзов") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                inventory.error?.let { item { ErrorCard(it, vm::refresh) } }
                val visible = inventory.gateways.filter { "${it.name} ${it.host} ${it.raw.text("group")}".contains(query, ignoreCase = true) }
                if (visible.isEmpty()) item { PanelCard { Text(if (query.isBlank()) "Шлюзы ещё не добавлены" else "Шлюзы не найдены"); if (query.isBlank()) PanelButton(onClick = onAdd, enabled = inventory.enabled) { Text("Добавить шлюз") } } }
                items(visible, key = { it.id }) { GatewayCard(it) { onGateway(it.id) } }
            }
        }
    }
}

@Composable
fun GatewayDetailScreen(id: String, onBack: () -> Unit, onTerminal: (String) -> Unit, onHistory: (String) -> Unit, vm: GatewayViewModel = hiltViewModel()) {
    LaunchedEffect(id) { vm.open(id) }
    ObserveGateways(vm)
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    val node = vm.node
    val (sessionUpdate, sessionHistory) = rememberGatewaySessions(id, node?.id == id && vm.page == "overview") {
        vm.loadActiveSessions(id)
    }
    LaunchedEffect(vm.task, inventory.gateways) { val task = vm.task; if (task != null && task.text("kind") == "openwrt-disconnect" && gatewayTaskSucceeded(task) && inventory.gateways.none { it.id == id }) onBack() }
    var form by remember(id) { mutableStateOf<GatewayForm?>(null) }
    var dhcpOpen by remember(id) { mutableStateOf(false) }
    var formAction by remember(id) { mutableStateOf("network") }
    var info by remember(id) { mutableStateOf<JsonObject?>(null) }
    var infoTitle by remember { mutableStateOf("") }
    var browserService by remember(id) { mutableStateOf<String?>(null) }
    browserService?.let { service -> com.bot4vps.mobile.ui.browser.ServiceBrowserDialog(id, service, { browserService = null }) }
    LaunchedEffect(vm.postInstallAdguardHelp) {
        vm.postInstallAdguardHelp?.let { data -> infoTitle = "AdGuard Home"; info = data; vm.consumeAdguardHelp() }
    }
    var install by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<JsonObject?>(null) }
    val context = LocalContext.current
    LaunchedEffect(vm.error) { vm.error?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() } }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::uploadBackup) }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val archive = pendingDownload; pendingDownload = null
        if (uri != null && archive != null) vm.downloadBackup(archive.text("id").orEmpty(), uri)
    }
    fun edit(value: GatewayForm, action: String) { formAction = action; form = value }
    GatewayReviewDialog(vm) { applied ->
        if (applied.data.text("action") == "dhcp_reservation") dhcpOpen = false
    }
    if (dhcpOpen && node != null) {
        GatewayDhcpLeases(
            node, vm, visible = form == null && vm.review == null,
            onClose = { dhcpOpen = false },
            onReserve = { edit(it, "dhcp_reservation") },
        )
    }
    form?.takeIf { vm.review == null }?.let { current -> GatewayFormDialog(current, vm.busy, vm.error, { form = null }) { data ->
        when (formAction) {
            "access" -> vm.saveAccess(data)
            "ssh" -> vm.saveSsh(data)
            "root-password" -> vm.saveRootPassword(data)
            else -> vm.networkPlan(formAction, data)
        }
    } }
    LaunchedEffect(vm.review, vm.notice) { if (vm.review != null || vm.notice != null) form = null }
    info?.let { data -> if (infoTitle == "AdGuard Home" && node != null) GatewayAdguardDialog(node, data, vm, { info = null }, { install = "adguardhome"; vm.loadCatalog("adguardhome") }, { value -> edit(value, "dns") }, { browserService = "adguardhome"; info = null }) else GatewayInfoDialog(infoTitle, data) { info = null } }
    if (install != null) GatewayInstallDialog(install!!, vm, { install = null })
    PanelScaffold(topBar = { PanelTopBar(title = { Text(node?.name ?: "Шлюз", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }, actions = {
        IconButton(enabled = node != null && !vm.busy, onClick = vm::deletion) { Icon(Icons.Default.DeleteOutline, "Удалить шлюз", tint = MaterialTheme.colorScheme.error) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (node != null && vm.rootPasswordConfigured == false) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Пароль root не задан", Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        TextButton(onClick = { vm.selectPage("access") }, enabled = !vm.busy) { Text("Открыть «Доступ»") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("overview" to "Обзор", "network" to "Сеть", "wireless" to "Wi-Fi", "services" to "Сервисы", "packages" to "Пакеты", "backups" to "Копии", "logs" to "Журнал", "access" to "Доступ").forEach { (page, label) ->
                    FilterChip(selected = vm.page == page, onClick = { vm.selectPage(page) }, enabled = !vm.busy && vm.review == null, label = { Text(label) })
                }
            }
            Refreshable(vm.busy, vm::refresh, Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = refreshArrangement(12.dp)) {
                    vm.error?.let { item { ErrorCard(it, vm::refresh) } }
                    vm.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                    if (node == null) item { PanelCard { Text(if (vm.busy) "Загрузка шлюза…" else "Не удалось загрузить шлюз") } }
                    if (node != null) {
                        if (!node.online) item { PanelCard { Text("Показаны последние полученные данные. ${node.monitor.text("error").orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                        if (node.raw.text("bootstrap_state") == "commit_pending") item { PanelCard("Подключение сохранено") { Text("Осталась фиксация интеграции. Повторное подключение не требуется."); PanelButton(enabled = !vm.busy, onClick = vm::finalizeBootstrap) { Text("Завершить фиксацию") } } }
                        when (vm.page) {
                            "overview" -> {
                                item(key = "gateway-overview") { GatewayOverview(node, vm, onTerminal, { browserService = "luci" }) }
                                item(key = "gateway-traffic") {
                                    GatewayTraffic(vm.traffic, node.monitor, vm.period, vm.hasQuarterHourTraffic, vm::trafficPeriod)
                                }
                                item(key = "gateway-active-sessions") { GatewayActiveSessions(node.id, sessionUpdate, sessionHistory) }
                                item(key = "gateway-wireguard") { GatewayWireguard(node.monitor) }
                            }
                            "network" -> {
                                item { PanelCard("Подключения") {
                                    GatewayActionRow(listOf("Добавить подключение" to { edit(gatewayInterfaceForm(vm.content), "edit") }, "Статические маршруты" to { edit(gatewayRoutesForm(vm.content), "routes") }), node.controls && !vm.busy && vm.content.text("config_hash") != null)
                                } }
                                items(vm.content.objects("interfaces"), key = { it.text("name").orEmpty() }) { iface ->
                                    val name = iface.text("name").orEmpty()
                                    val live = node.monitor.objects("interfaces").find { it.text("name") == name } ?: iface
                                    val management = iface.text("ipaddr") == node.host || node.host in iface.strings("runtime_addresses")
                                    PanelCard {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                            StatusBadge(if (live.flag("up")) "Подключён" else "Отключён", if (live.flag("up")) StatusTone.GOOD else StatusTone.WARNING)
                                        }
                                        InfoRow("Протокол", iface.text("proto").orEmpty()); InfoRow("Устройство", iface.text("device") ?: iface.text("runtime_device").orEmpty())
                                        InfoRow("Адреса", live.strings("addresses").ifEmpty { iface.strings("runtime_addresses") }.joinToString(", ").ifBlank { iface.text("ipaddr").orEmpty() })
                                        if (management) Text("Интерфейс доступа к панели", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                        FlowActions(listOf("Настроить" to { edit(gatewayInterfaceForm(vm.content, iface), "edit") }, "DHCP" to { edit(gatewayDhcpForm(vm.content, name), "dhcp") }, "Переподключить" to { vm.networkPlan("reconnect", buildJsonObject { put("interface", name); put("config_hash", vm.content.text("config_hash")) }) }), node.controls && !vm.busy)
                                        if (!management) FlowActions(listOf((if (live.flag("up")) "Отключить" else "Подключить") to { vm.networkPlan(if (live.flag("up")) "down" else "up", buildJsonObject { put("interface", name); put("config_hash", vm.content.text("config_hash")) }) }, "Удалить" to { vm.networkPlan("delete", buildJsonObject { put("interface", name); put("config_hash", vm.content.text("config_hash")) }) }), node.controls && !vm.busy)
                                    }
                                }
                                items(vm.content.objects("routes")) { route -> PanelCard { InfoRow(route.text("target").orEmpty(), "${route.text("gateway") ?: "Напрямую"} · ${route.text("interface")}") } }
                            }
                            "wireless" -> {
                                if (vm.content.objects("radios").isEmpty()) item { PanelCard { Text(if (vm.busy) "Получаем состояние Wi-Fi…" else "Радиомодули не найдены") } }
                                items(vm.content.objects("radios"), key = { it.text("name").orEmpty() }) { radio -> PanelCard("${radio.text("name")} · ${radio.text("band").orEmpty()}") {
                                    InfoRow("Канал", radio.text("runtime_channel") ?: radio.text("channel").orEmpty()); InfoRow("Режим", radio.text("htmode").orEmpty())
                                    radio.objects("interfaces").forEach { wifi -> InfoRow(wifi.text("ssid") ?: wifi.text("id").orEmpty(), if (wifi.text("disabled") == "1") "Выключена" else wifi.text("encryption") ?: "—") }
                                    FlowActions(listOf("Настроить" to { edit(gatewayWifiForm(vm.content, radio), "wireless") }, "Сканировать эфир" to { vm.scan(radio.text("name").orEmpty()) { infoTitle = "Эфир Wi-Fi · ${radio.text("name")}"; info = JsonObject(it + ("selected_radio" to radio)) } }), node.controls && !vm.busy)
                                } }
                            }
                            "services" -> {
                                item { PanelCard("Сервисы шлюза") {
                                    FlowActions(listOf((if (vm.checkingServiceUpdates) "Проверяю…" else "Проверить обновления") to { vm.selectPage("services") }, "Установить сервис" to { install = "podkop"; vm.loadCatalog("podkop") }), node.controls && !vm.busy)
                                    Text(if (vm.checkingServiceUpdates) "Проверяю установленные версии и совместимые релизы…" else vm.content.number("checked_at")?.let { "Проверено в ${epochTime(it)}" } ?: "Версии ещё не проверены", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!node.controls) Text("Управление недоступно. Проверьте связь и интеграцию во вкладке «Доступ».", style = MaterialTheme.typography.bodySmall)
                                } }
                                val services = node.services
                                val firewall = services.indexOfFirst { it.text("id") == "firewall" }
                                val dnsmasq = services.indexOfFirst { it.text("id") == "dnsmasq" }
                                val dhcpPosition = (if (firewall >= 0) firewall else dnsmasq) + 1
                                for (index in 0..services.size) {
                                    if (index == dhcpPosition) {
                                        item(key = "gateway-dhcp-leases") {
                                            PanelCard {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("DHCP-аренды", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                    TextButton(onClick = { dhcpOpen = true }, enabled = !vm.busy) { Text("Открыть") }
                                                }
                                            }
                                        }
                                    }
                                    val service = services.getOrNull(index) ?: continue
                                    item(key = "gateway-service-${service.text("id")}") {
                                        GatewayServiceCard(node, service, vm, edit = { value, action -> edit(value, action) }, guide = { infoTitle = "AdGuard Home"; info = it }, browser = { browserService = it })
                                    }
                                }
                                item { GatewayReserve(vm) }
                            }
                            "packages" -> { item { GatewayPackages(vm, node.controls) } }
                            "backups" -> {
                                item { PanelCard("Резервные копии настроек") {
                                    Text("Штатный архив OpenWrt. Прошивка и пакеты в копию не входят. Копии общие для всех шлюзов.", style = MaterialTheme.typography.bodySmall)
                                    PanelButton(onClick = vm::createBackup, enabled = vm.content.flag("available") && node.online && !vm.busy && vm.content["task"] !is JsonObject) { Text("Создать копию") }
                                    PanelSecondaryButton(onClick = { upload.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")) }, enabled = !vm.busy && vm.content["task"] !is JsonObject) { Text("Загрузить свой архив") }
                                    if (!vm.content.flag("available")) Text("Для создания и восстановления настройте управляемый API во вкладке «Доступ».", style = MaterialTheme.typography.bodySmall)
                                    if (vm.content.flag("reboot_required")) { Text("Файлы восстановлены. Для применения нужна перезагрузка.", color = MaterialTheme.colorScheme.primary); PanelButton(onClick = { vm.backupPlan("reboot") }, enabled = !vm.busy && node.online && vm.content["task"] !is JsonObject) { Text("Перезагрузить и применить") } }
                                    (vm.content["task"] as? JsonObject)?.let { active -> TextButton(onClick = { vm.track(active) }) { Text("Показать текущую задачу") } }
                                } }
                                items(vm.content.objects("items"), key = { it.text("id").orEmpty() }) { archive -> PanelCard(archive.text("filename")) {
                                    InfoRow("Источник", archive.text("source_name").orEmpty()); InfoRow("Модель / версия", "${archive.text("model") ?: "—"} · ${archive.text("version") ?: "—"}")
                                    InfoRow("Размер", displayBytes(archive.number("size"))); InfoRow("Создана", epochTime(archive.number("created_at")))
                                    FlowActions(listOf("Восстановить" to { vm.backupPlan("restore", archive.text("id")) }), !vm.busy && node.online && vm.content.flag("available") && vm.content["task"] !is JsonObject)
                                    FlowActions(listOf("Скачать" to { pendingDownload = archive; download.launch(archive.text("filename") ?: "openwrt-backup.tar.gz") }, "Удалить" to { vm.deleteBackup(archive) }), !vm.busy)
                                } }
                                if (vm.content.objects("items").isEmpty()) item { Text("Сохранённых копий пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            "logs" -> item { PanelCard("Журнал шлюза") { SelectionContainer { Text(vm.content.text("output") ?: "Журнал пуст", style = MaterialTheme.typography.bodySmall) } } }
                            "access" -> item { PanelCard("Доступ к шлюзу") {
                                InfoRow("API", node.raw.text("api_url").orEmpty()); InfoRow("Учётная запись", node.raw.text("api_username").orEmpty()); InfoRow("Режим", if (node.raw.flag("managed")) "Управляемый" else "Ручной")
                                node.raw.text("tls_sha256")?.let { SelectionContainer { Text("SHA-256: $it", style = MaterialTheme.typography.bodySmall) } }
                                FlowActions(listOf("Настроить API" to { edit(gatewayAccessForm(node), "access") }, "SSH-доступ" to { vm.loadKeys { keys -> edit(gatewaySshForm(node, keys), "ssh") } }), !vm.busy)
                                val rootSsh = node.raw.flag("ssh_configured") && node.raw.text("ssh_user") == "root"
                                PanelSecondaryButton(onClick = { edit(gatewayRootPasswordForm(vm.rootPasswordConfigured), "root-password") },
                                    enabled = !vm.busy && rootSsh) {
                                    Text(if (vm.rootPasswordConfigured == false) "Задать пароль root" else "Сменить пароль root")
                                }
                                if (!rootSsh) Text("Для смены пароля сохраните SSH-доступ под root.", style = MaterialTheme.typography.bodySmall)
                                PanelButton(onClick = vm::integration, enabled = !vm.busy && node.raw.flag("ssh_configured") && node.raw.flag("managed")) { Text(if (node.raw.flag("deletion_pending")) "Восстановить отозванный API через root SSH" else "Обновить интеграцию Bot4VPS") }
                                PanelSecondaryButton(onClick = { vm.backupPlan("reboot") }, enabled = node.controls && !vm.busy) { Text("Перезагрузить шлюз") }
                            } }
                        }
                    }
                    vm.task?.let { item { GatewayTaskCard(vm, onHistory) } }
                    if (!inventory.enabled) item { Text("Шлюзы выключены в настройках панели") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowActions(actions: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEach { (label, action) -> TextButton(onClick = action, enabled = enabled) { Text(label) } }
    }
}

@Composable
private fun GatewayActionRow(actions: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    val text = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { ((maxWidth - 6.dp * (actions.size - 1)) / actions.size - 4.dp).toPx() }
        val longest = actions.maxOf { text.measure(it.first, style, softWrap = false, maxLines = 1).size.width }.coerceAtLeast(1)
        val fitting = style.copy(fontSize = style.fontSize * (available / longest).coerceIn(.5f, 1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.forEach { (label, action) -> TextButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                Text(label, style = fitting, maxLines = 1, softWrap = false)
            } }
        }
    }
}

@Composable
private fun GatewayOverview(node: Gateway, vm: GatewayViewModel, onTerminal: (String) -> Unit, onLuci: () -> Unit) {
    val monitor = node.monitor; val board = monitor["board"] as? JsonObject
    PanelCard("Состояние шлюза") {
        StatusBadge(if (node.online) "В сети" else "Нет свежих данных", if (node.online) StatusTone.GOOD else StatusTone.WARNING)
        InfoRow("Модель", board?.text("model").orEmpty()); InfoRow("OpenWrt", (board?.get("release") as? JsonObject)?.text("version").orEmpty())
        InfoRow("Время работы", uptime(monitor.number("uptime"))); InfoRow("Последний сбор", epochTime(monitor.number("checked_at")))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatTile("CPU", percent(monitor.number("cpu_pct")), Modifier.weight(1f), monitor.number("cpu_pct")); StatTile("Память", percent(monitor.number("ram_pct")), Modifier.weight(1f), monitor.number("ram_pct")) }
        InfoRow("RAM", "${displayBytes(monitor.number("ram_used"))} / ${displayBytes(monitor.number("ram_total"))}")
        InfoRow("Хранилище свободно", "${displayBytes(monitor.number("storage_free"))} / ${displayBytes(monitor.number("storage_total"))}")
        InfoRow("Температура", monitor.number("temperature")?.let { "%.1f °C".format(it) } ?: "Датчик недоступен")
        InfoRow("Нагрузка", monitor.strings("load").joinToString(" · "))
        GatewayActionRow(listOf("Консоль шлюза" to { if (node.raw.flag("ssh_configured")) onTerminal(node.id) else vm.selectPage("access") }, "LuCI" to onLuci), !vm.busy)
    }
}

@Composable
private fun GatewayTraffic(
    history: List<JsonObject>, monitor: JsonObject, period: Int, hasQuarterHour: Boolean, change: (Int) -> Unit,
) {
    PanelCard("Трафик") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasQuarterHour) FilterChip(period == 900, { change(900) }, label = { Text("15 мин") })
            listOf(3600 to "1 ч", 21600 to "6 ч", 43200 to "12 ч", 86400 to "24 ч").forEach { (seconds, title) ->
                FilterChip(period == seconds, { change(seconds) }, label = { Text(title) })
            }
        }
        listOf("wan" to "WAN", "lan" to "LAN").forEach { (role, title) ->
            val current = (monitor["traffic"] as? JsonObject)?.get(role) as? JsonObject
            Text(title, style = MaterialTheme.typography.titleSmall)
            InfoRow("↓ Приём / ↑ Передача", "${displayBytes(current?.number("rx_bps"))}/с · ${displayBytes(current?.number("tx_bps"))}/с")
            GatewayLineChart(history, role)
            InfoRow("Всего принято / отправлено", "${displayBytes(current?.number("rx_bytes"))} · ${displayBytes(current?.number("tx_bytes"))}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("● Приём", color = Color(0xFF60A5FA), style = MaterialTheme.typography.labelSmall); Text("● Передача", color = Color(0xFFF472B6), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun GatewayLineChart(history: List<JsonObject>, role: String) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val end = history.lastOrNull()?.number("at") ?: 0.0; val start = history.firstOrNull()?.number("at") ?: end
    val max = history.flatMap { point -> val values = point[role] as? JsonObject; listOfNotNull(values?.number("rx_bps"), values?.number("tx_bps")) }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    if (history.isEmpty()) Text("История трафика ещё не накоплена", style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        repeat(4) { line -> val y = size.height * line / 3; drawLine(grid.copy(alpha = .4f), Offset(0f, y), Offset(size.width, y)) }
        listOf("rx_bps" to Color(0xFF60A5FA), "tx_bps" to Color(0xFFF472B6)).forEach { (key, color) ->
            val path = Path(); var joined = false
            history.forEach { point ->
                val value = (point[role] as? JsonObject)?.number(key); val at = point.number("at")
                if (value == null || at == null || !value.isFinite()) joined = false
                else { val x = ((at - start) / (end - start).coerceAtLeast(1.0) * size.width).toFloat(); val y = (size.height * (1 - value / max)).toFloat(); if (joined) path.lineTo(x, y) else path.moveTo(x, y); joined = true }
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(epochTime(start), style = MaterialTheme.typography.labelSmall); Text(epochTime(end), style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun GatewayDhcpLeases(node: Gateway, vm: GatewayViewModel, visible: Boolean, onClose: () -> Unit, onReserve: (GatewayForm) -> Unit) {
    var static by remember(node.id) { mutableStateOf(false) }
    var query by remember(node.id, static) { mutableStateOf("") }
    var count by remember(node.id, static) { mutableIntStateOf(20) }
    var retry by remember(node.id) { mutableIntStateOf(0) }
    LaunchedEffect(node.id, node.online, retry) { vm.loadDhcpReservations() }
    if (!visible) return
    val monitor = node.monitor
    val leases = monitor.objects("clients")
    val capability = (monitor["capabilities"] as? JsonObject)?.get("clients") as? JsonObject
    val available = capability?.text("available")?.toBooleanStrictOrNull()
        ?: (monitor["clients"] is JsonArray)
    val config = vm.dhcpConfig
    val reservations = config?.objects("dhcp_reservations").orEmpty()
    val canReserve = config != null && config.text("settings_hash") != null && node.controls && !vm.busy && !vm.dhcpLoading && vm.dhcpError == null
    val rows = (if (static) reservations else leases).filter { row ->
        val name = row.text(if (static) "name" else "hostname").orEmpty()
        val ip = row.text(if (static) "ip" else "ipaddr").orEmpty()
        val mac = if (static) row.reservationMacs().joinToString(", ") else row.text("macaddr").orEmpty()
        listOf(name, ip, mac).any { it.contains(query, ignoreCase = true) }
    }
    PanelAlertDialog(
        onDismissRequest = { if (!vm.busy) onClose() },
        title = { Text("DHCP-аренды") },
        confirmButton = { TextButton(onClick = onClose, enabled = !vm.busy) { Text("Закрыть") } },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !static, onClick = { static = false }, label = { Text("Текущие") })
            FilterChip(selected = static, onClick = { static = true }, label = { Text("Статические") })
        }
        if (canReserve && config != null) {
            PanelSecondaryButton(onClick = { onReserve(gatewayDhcpReservationForm(config)) }) {
                Text("Закрепить IPv4 вручную")
            }
        }
        val dhcpError = vm.dhcpError
        when {
            !node.online -> Text("Шлюз недоступен. Показаны последние данные.", style = MaterialTheme.typography.bodySmall)
            vm.dhcpLoading -> Text("Загружаем статические аренды…", style = MaterialTheme.typography.bodySmall)
            dhcpError != null -> {
                Text("Не удалось определить тип аренды: $dhcpError", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("Повторить") }
            }
            config == null -> Text("Тип аренды недоступен. Обновите интеграцию Bot4VPS во вкладке «Доступ».", style = MaterialTheme.typography.bodySmall)
        }
        val emptyMessage = when {
            static && config == null -> null
            static && reservations.isEmpty() -> "Нет статических IPv4-аренд"
            !static && !available -> "Данные об аренде IPv4 недоступны"
            !static && leases.isEmpty() -> "Нет выданных IPv4-адресов"
            else -> null
        }
        if (emptyMessage != null) Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (emptyMessage != null || static && config == null) return@Column
        OutlinedTextField(query, { query = it; count = 20 }, label = { Text("Имя, IP или MAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        rows.take(count).forEach { row ->
            val name = row.text(if (static) "name" else "hostname")?.takeIf { it.isNotBlank() && it != "*" }
            val ip = row.text(if (static) "ip" else "ipaddr").orEmpty()
            val mac = if (static) row.reservationMacs().joinToString(", ") else row.text("macaddr").orEmpty()
            val typeKnown = config != null && !vm.dhcpLoading && dhcpError == null
            val fixed = static || typeKnown && dhcpReservationForLease(ip, mac, reservations) != null
            val canPin = canReserve && !fixed && mac.isNotBlank() && ip.isNotBlank() && reservations.none {
                it.text("ip") == ip || it.reservationMacs().any { value -> value.equals(mac, ignoreCase = true) }
            }
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name ?: "Без имени", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ip.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(mac.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(
                    Modifier.width(96.dp).fillMaxHeight().padding(top = if (fixed) 0.dp else 3.dp),
                    verticalArrangement = if (fixed) Arrangement.Center else Arrangement.Top,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (fixed || typeKnown) {
                            Canvas(Modifier.size(7.dp)) {
                                drawCircle(if (fixed) Color(0xFF3B82F6) else Color(0xFFEAB308))
                            }
                        }
                        Text(if (fixed) "Статическая" else if (typeKnown) "Аренда" else "—",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                    if (canPin && config != null) {
                        TextButton(
                            onClick = { onReserve(gatewayDhcpReservationForm(config, row)) },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("Закрепить IP", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                    }
                }
                if (canReserve && config != null && static) {
                    IconButton(onClick = { onReserve(gatewayDhcpReservationForm(config, reservation = row)) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, "Изменить статическую аренду ${name ?: ip}", Modifier.size(19.dp))
                    }
                    IconButton(onClick = { vm.deleteDhcpReservation(config, row) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.DeleteOutline, "Удалить статическую аренду ${name ?: ip}", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (rows.isEmpty()) Text("Аренды не найдены", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.size > count) TextButton(onClick = { count += 20 }) { Text("Показать ещё") }
        } },
    )
}

@Composable
private fun GatewayWireguard(monitor: JsonObject) {
    val values = monitor["wireguard"] as? JsonObject ?: return
    if (values.isEmpty()) return
    PanelCard("WireGuard") { values.forEach { (name, value) ->
        val iface = value as? JsonObject ?: return@forEach
        Text("$name · порт ${iface.text("listen_port") ?: "—"}", style = MaterialTheme.typography.titleSmall)
        iface.objects("peers").forEach { peer -> InfoRow(peer.text("endpoint") ?: "Пир", "${peer.strings("allowed_ips").joinToString(", ")}\n${if (peer.flag("recent_handshake")) "Связь активна" else "Нет свежего рукопожатия"}\n↓ ${displayBytes(peer.number("rx_bytes"))} · ↑ ${displayBytes(peer.number("tx_bytes"))}") }
    } }
}

@Composable
private fun GatewayServiceCard(node: Gateway, service: JsonObject, vm: GatewayViewModel, edit: (GatewayForm, String) -> Unit, guide: (JsonObject) -> Unit, browser: (String) -> Unit) {
    val context = LocalContext.current
    val id = service.text("id").orEmpty()
    val updates = (vm.content["services"] as? JsonObject)?.get(id) as? JsonObject
    val pending = updates?.text("stage") ?: node.raw.objects("service_update_tasks").find { it.text("service") == id }?.text("stage")
    val canAct = node.controls && !vm.busy && pending == null
    var showVersion by remember(node.id, id) { mutableStateOf(false) }
    var versionInput by remember(node.id, id) { mutableStateOf("") }
    val fingerprint = updates?.text("fingerprint")
    if (showVersion && fingerprint != null) PanelAlertDialog(
        onDismissRequest = { if (!vm.busy) showVersion = false },
        title = { Text("Установленная версия · ${gatewayServiceName(id)}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Укажите версию из интерфейса сервиса. Сохраняется только запись о версии; сервис и настройки не меняются.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(versionInput, { versionInput = it.trim() }, label = { Text("Установленная версия") }, placeholder = { Text("Например, 7.7.4") }, singleLine = true, enabled = !vm.busy)
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { vm.confirmInstalledVersion(id, versionInput, fingerprint) { showVersion = false } }, enabled = !vm.busy && Regex("v?[0-9]+[.][0-9]+[.][0-9]+").matches(versionInput)) { Text(if (vm.busy) "Сохраняю…" else "Подтвердить версию") } },
        dismissButton = { TextButton(onClick = { showVersion = false }, enabled = !vm.busy) { Text("Отмена") } }
    )
    PanelCard(gatewayServiceName(id)) {
        if (id in listOf("podkop", "ssclash", "adguardhome")) ServiceProjectInfo(id, help = if (id == "adguardhome") { { vm.adguard(guide) } } else null)
        val updateLabel = when {
            id in listOf("dnsmasq", "firewall") -> ""
            vm.checkingServiceUpdates -> "Проверяю обновления…"
            updates?.flag("available") == true -> "Есть обновление до ${updates.text("latest")}"
            updates?.text("status") == "current_cached" -> "Актуальная среди сохранённых версий"
            updates?.text("warning") != null -> "Проверка не завершена"
            updates?.text("status") == "newer" -> "Установленная версия новее доступного релиза"
            updates?.text("current") != null && updates.text("latest") != null -> "Актуальная версия"
            vm.error != null -> "Не удалось проверить обновления"
            else -> "Обновления не проверены"
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.widthIn(min = 90.dp)) {
                Text("Версия", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(updates?.text("installed_version") ?: updates?.text("current") ?: service.text("version") ?: "—", style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(if (service.flag("running")) "Работает" else "Остановлен", if (service.flag("running")) StatusTone.GOOD else StatusTone.WARNING, maxLines = 1)
                if (updateLabel.isNotEmpty()) StatusBadge(updateLabel, if (updates?.flag("available") == true || updates?.text("warning") != null || vm.error != null) StatusTone.WARNING else if (updates?.text("current") != null && updates.text("latest") != null) StatusTone.GOOD else StatusTone.NEUTRAL, maxLines = 1)
            }
        }
        updates?.text("warning")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pending != null) Text(when (pending) { "prepare" -> "Готовится обновление"; "review" -> "Обновление ожидает подтверждения"; else -> "Выполняется обновление" }, style = MaterialTheme.typography.bodySmall)
        if (id in listOf("ssclash", "adguardhome") && updates?.text("current") == null && fingerprint != null) PanelSecondaryButton(onClick = { versionInput = ""; showVersion = true }, enabled = canAct && !vm.checkingServiceUpdates) { Text("Указать установленную версию") }
        if (updates?.flag("available") == true || pending != null) PanelButton(onClick = { vm.updateService(id) }, enabled = node.controls && !vm.busy) { Text(if (pending == "review") "Продолжить обновление" else if (pending != null) "Показать задачу обновления" else "Обновить до ${updates?.text("latest")}") }
        fun configure() {
            when (id) {
                "dnsmasq", "firewall" -> vm.act {
                    val config = vm.call("GET", "${vm.base}/network")
                    if (id == "firewall") edit(gatewayFirewallForm(config), "firewall")
                    else {
                        val instances = config.objects("dns")
                        require(instances.isNotEmpty()) { "DNSMasq не настроен" }
                        edit(gatewayDnsForm(config, instances.first()), "dns")
                    }
                }
                "adguardhome" -> vm.adguard(guide)
                else -> {
                    browser(id)
                }
            }
        }
        if (id in listOf("dnsmasq", "firewall")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (id == "firewall") vm.restartFirewall() else vm.serviceAction(id, "restart") }, enabled = canAct, modifier = Modifier.weight(1f)) { Text("Перезапустить", maxLines = 1, softWrap = false) }
                TextButton(onClick = ::configure, enabled = canAct, modifier = Modifier.weight(1f)) { Text("Настроить", maxLines = 1, softWrap = false) }
            }
        } else {
            val lifecycleActions = buildList {
                if (service.flag("running")) add("Перезапустить" to { vm.serviceAction(id, "restart") })
                add((if (service.flag("running")) "Остановить" else "Запустить") to { vm.serviceAction(id, if (service.flag("running")) "stop" else "start") })
                if (id in listOf("podkop", "ssclash") || id == "adguardhome" && service.flag("managed")) add("Удалить" to { vm.serviceAction(id, "remove") })
            }
            GatewayActionRow(lifecycleActions, canAct)
            if (id == "ssclash" && service.flag("managed")) PanelSecondaryButton(onClick = { vm.serviceAction(id, "web_access", buildJsonObject { put("web_scope", if (service.text("web_scope") == "wan") "lan" else "wan") }) }, enabled = canAct) { Text(if (service.text("web_scope") == "wan") "Доступ только из LAN" else "Разрешить доступ из WAN") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { if (id == "adguardhome") browser(id) else configure() }, enabled = !vm.busy && node.online) { Text(if (id == "adguardhome") "Открыть AdGuard Home" else "Открыть интерфейс сервиса", maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun GatewayReserve(vm: GatewayViewModel) {
    val state by vm.reserveState.collectAsStateWithLifecycle()
    com.bot4vps.mobile.ui.files.ServiceReserveCard(state, vm.busy, vm::setReserve)
}

@Composable
private fun GatewayPackages(vm: GatewayViewModel, controls: Boolean) {
    var query by remember { mutableStateOf("") }; var installed by remember { mutableStateOf(false) }
    val rows = vm.content.objects("items"); val offset = vm.content.number("offset")?.toInt() ?: 0
    PanelCard("Пакеты · ${vm.content.text("manager") ?: "OpenWrt"}") {
        InfoRow("Установлено", vm.content.text("installed_count") ?: "—")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!installed, { installed = false; vm.packages(query, false) }, label = { Text("Доступные") }); FilterChip(installed, { installed = true; vm.packages(query, true) }, label = { Text("Установленные") }) }
        OutlinedTextField(query, { query = it.take(100) }, label = { Text("Название или описание") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowActions(listOf("Найти" to { vm.packages(query, installed) }, "Обновить каталог" to { vm.packagePlan("refresh") }), !vm.busy)
        Text("Обновление каталога не обновляет прошивку и установленные пакеты.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { row -> HorizontalDivider(); Text(row.text("name").orEmpty(), fontWeight = FontWeight.SemiBold); Text("${row.text("version") ?: "—"} · ${row.text("description").orEmpty()}", style = MaterialTheme.typography.bodySmall)
            if (!row.flag("installed") && !installed) TextButton(onClick = { vm.packagePlan("install", row.text("name").orEmpty()) }, enabled = controls && !vm.busy) { Text("Установить") }
        }
        if (rows.isEmpty()) Text("Пакеты не найдены", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.packages(query, installed, (offset - 50).coerceAtLeast(0)) }, enabled = !vm.busy && offset > 0) { Text("Назад") }
            TextButton(onClick = { vm.packages(query, installed, offset + rows.size) }, enabled = !vm.busy && offset + rows.size < (vm.content.number("total") ?: 0.0)) { Text("Далее") }
        }
    }
}

@Composable
private fun GatewayInstallDialog(initial: String, vm: GatewayViewModel, dismiss: () -> Unit) {
    var service by remember { mutableStateOf(initial) }; var version by remember(vm.catalog) { mutableStateOf(vm.catalog?.strings("versions")?.firstOrNull().orEmpty()) }
    PanelAlertDialog(onDismissRequest = { if (!vm.busy) dismiss() }, title = { Text("Установить сервис") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("podkop", "ssclash", "adguardhome").forEach { id -> FilterChip(service == id, { service = id; vm.loadCatalog(id) }, enabled = !vm.busy, label = { Text(gatewayServiceName(id)) }) } }
        ServiceProjectInfo(service)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.catalog?.flag("offline") == true) Text("Источник недоступен. Показаны сохранённые версии.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { vm.catalog?.strings("versions")?.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(version == item, { version = item }); Text(item) } } }
        Text("Сначала панель подготовит комплект. Установка начнётся после просмотра и подтверждения плана.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { vm.installBundle(service, version); dismiss() }, enabled = !vm.busy && version.isNotBlank()) { Text("Подготовить") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !vm.busy) { Text("Отмена") } })
}

@Composable
fun GatewayReviewDialog(vm: GatewayViewModel, onApplied: (GatewayReview) -> Unit = {}) {
    val review = vm.review ?: return
    PanelAlertDialog(onDismissRequest = { if (!vm.busy) vm.cancelReview() }, title = { Text(review.title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val data = review.data
            data.text("filename")?.let { Text("Архив: $it · ${data.text("files")} файлов") }
            data.text("version")?.let { Text("Версия: $it") }
            data.text("interface")?.let { Text("Подключение: $it") }
            listOf("summary" to "Изменения", "steps" to "Порядок действий", "changes" to "Изменения", "removed" to "Будет удалено", "preserved" to "Будет сохранено", "warnings" to "Последствия", "files" to "Файлы интеграции").forEach { (key, title) ->
                val list = data[key] as? JsonArray
                if (list != null && list.isNotEmpty()) { Text(title, fontWeight = FontWeight.SemiBold); list.forEach { row ->
                    Text(if (row is JsonObject) "${row.text("package") ?: row.text("name")} · ${row.text("before") ?: "—"} → ${row.text("after") ?: displayBytes(row.number("size"))}" else gatewayText(row), style = MaterialTheme.typography.bodySmall)
                } }
            }
            if (data.flag("address_changes")) Text("Адрес API изменится: ${data.text("new_api_host")}. Панель сохранит его после проверки.", color = MaterialTheme.colorScheme.error)
            data.number("rollback_seconds")?.let { Text("Если панель не подтвердит связь за ${it.toInt()} секунд, роутер вернёт прежние настройки.", style = MaterialTheme.typography.bodySmall) }
            if (data.flag("network_check")) Text("После операции панель проверит сеть и состояние сервиса.", style = MaterialTheme.typography.bodySmall)
            if (data.flag("package_scripts")) Text("Установка запускает служебные скрипты пакета на роутере.", style = MaterialTheme.typography.bodySmall)
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { vm.confirm(onApplied) }, enabled = !vm.busy) { Text(if (vm.busy) "Подождите…" else "Подтвердить") } }, dismissButton = { TextButton(onClick = vm::cancelReview, enabled = !vm.busy) { Text("Отмена") } })
}

@Composable
fun GatewayTaskCard(vm: GatewayViewModel, onHistory: (String) -> Unit) {
    val task = vm.task ?: return
    PanelCard(task.text("name") ?: "Операция шлюза") {
        Text(if (vm.taskMissing) "Задача не найдена. Результат неизвестен; повторять операцию автоматически нельзя." else gatewayTaskStatus(task.text("status").orEmpty()))
        if (!gatewayTaskFinished(task) && !vm.taskMissing) LinearProgressIndicator(Modifier.fillMaxWidth())
        val output = task.strings("output_lines").joinToString("\n").ifBlank { task.text("output") ?: (task["result"] as? JsonObject)?.text("output") ?: gatewayText(task["log"]) }
        (task["result"] as? JsonObject)?.strings("warnings")?.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (output.isNotBlank()) SelectionContainer { Text(output.takeLast(12000), style = MaterialTheme.typography.bodySmall) }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowActions(buildList { add("История задачи" to { task.text("id")?.let(onHistory) }); if (!vm.taskMissing) add("Проверить результат" to vm::resumeTask); add("Закрыть" to vm::dismissTask) }, !vm.busy)
    }
}

@Composable
private fun GatewayInfoDialog(title: String, value: JsonObject, dismiss: () -> Unit) {
    if (value["networks"] is JsonArray) { WirelessScanDialog(title, value, dismiss); return }
    PanelAlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        value.forEach { (key, data) -> if (data is JsonPrimitive || data is JsonArray) InfoRow(gatewayInfoLabels[key] ?: key, gatewayText(data)) }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}

@Composable
fun GatewayAddScreen(onBack: () -> Unit, onAdded: (String) -> Unit, onHistory: (String) -> Unit, vm: GatewayViewModel = hiltViewModel()) {
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<GatewayForm?>(null) }; var manual by remember { mutableStateOf(false) }; var instructions by remember { mutableStateOf(false) }
    form?.let { current -> GatewayFormDialog(current, vm.busy, vm.error, { form = null }) { body -> if (manual) vm.addManual(body, onAdded) else vm.probe(body) } }
    LaunchedEffect(vm.draft) { if (vm.draft != null) form = null }
    LaunchedEffect(vm.task, inventory.gateways) {
        val task = vm.task
        if (task != null && gatewayTaskFinished(task) && gatewayTaskSucceeded(task)) task.text("server_id")?.let { id -> if (inventory.gateways.any { it.id == id }) onAdded(id) }
    }
    vm.draft?.let { draft -> PanelAlertDialog(onDismissRequest = { if (!vm.busy) vm.discardDraft() }, title = { Text("Подключить OpenWrt?") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val board = draft["board"] as? JsonObject
            InfoRow("Модель", board?.text("model").orEmpty())
            if (board?.text("root_password_configured") == "false") Text("Пароль root не задан. После подключения задайте его во вкладке «Доступ».")
            draft.strings("changes").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (draft.strings("missing_packages").isNotEmpty()) Text("Будут установлены: ${draft.strings("missing_packages").joinToString(", ")}")
            TextButton(onClick = { instructions = !instructions }) { Text("Ручная настройка") }
            if (instructions) { val data = draft["manual"] as? JsonObject; data?.strings("steps")?.forEach { Text(it) }; SelectionContainer { Text(data?.strings("commands")?.joinToString("\n").orEmpty(), style = MaterialTheme.typography.bodySmall) } }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = vm::bootstrap, enabled = !vm.busy) { Text("Настроить автоматически") } }, dismissButton = { TextButton(onClick = vm::discardDraft, enabled = !vm.busy) { Text("Отмена") } }) }
    PanelScaffold(topBar = { PanelTopBar(title = { Text("Добавить шлюз") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PanelCard { Text("Подключите роутер OpenWrt. Панель создаст отдельный API-доступ либо проверит уже настроенную учётную запись.") } }
            item { ActionRow("По SSH", "Проверка роутера и автоматическая настройка Bot4VPS", Icons.Default.Terminal, { manual = false; vm.loadKeys { keys -> form = gatewaySshForm(keys = keys, add = true) } }, enabled = !vm.busy) }
            item { ActionRow("Через готовый API", "HTTPS, отдельная учётная запись и сертификат", Icons.Default.Link, { manual = true; form = gatewayAccessForm() }, enabled = !vm.busy) }
            vm.error?.let { item { ErrorCard(it) } }
            vm.task?.let { item { GatewayTaskCard(vm, onHistory) } }
        }
    }
}

private val gatewayInfoLabels = mapOf("installed" to "Установлен", "configured" to "Настроен", "web_port" to "Порт интерфейса", "setup_port_busy" to "Порт мастера занят", "dns_port" to "Порт DNS", "steps" to "Настройка", "warnings" to "Предупреждения", "note" to "Подсказка", "commands" to "Команды")
private fun percent(value: Double?) = value?.takeIf { it.isFinite() }?.let { "%.1f %%".format(it) } ?: "—"
private fun uptime(value: Double?) = value?.toLong()?.let { "${it / 86400} д ${it % 86400 / 3600} ч ${it % 3600 / 60} мин" } ?: "—"
private fun epochTime(value: Double?) = value?.takeIf { it > 0 && it.isFinite() }?.let { displayTime(Instant.ofEpochSecond(it.toLong()).toString()) } ?: "—"

fun gatewayTaskStatus(status: String) = mapOf("queued" to "В очереди", "running" to "Выполняется", "success" to "Завершено", "success_warn" to "Завершено с предупреждениями", "failed" to "Ошибка", "cancelled" to "Отменено")[status] ?: status
