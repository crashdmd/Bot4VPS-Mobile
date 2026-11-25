package com.bot4vps.mobile.ui.gateways

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.ui.components.*
import kotlinx.serialization.json.*

data class GatewayField(val key: String, val label: String, val initial: String = "", val choices: List<String> = emptyList(), val toggle: Boolean = false, val secret: Boolean = false, val required: Boolean = false, val locked: Boolean = false, val multiline: Boolean = false, val hint: String = "", val visible: (Map<String, String>) -> Boolean = { true })
data class GatewayRows(val key: String, val title: String, val fields: List<GatewayField>, val rows: List<JsonObject>, val add: Boolean = true, val remove: Boolean = true, val visible: (Map<String, String>) -> Boolean = { true }, val hint: String = "")
data class GatewayForm(val title: String, val fields: List<GatewayField>, val initial: JsonObject = JsonObject(emptyMap()), val groups: List<GatewayRows> = emptyList(), val hint: String = "", val submit: String = "Проверить изменения", val tabs: List<Pair<String, String>> = emptyList(), val result: (Map<String, String>, Map<String, List<Map<String, String>>>) -> JsonObject)

private fun initialValues(fields: List<GatewayField>, value: JsonObject): Map<String, String> = value.mapValues { gatewayText(it.value) } + fields.associate { it.key to (value[it.key]?.let(::gatewayText) ?: it.initial) }

@Composable
fun GatewayFields(fields: List<GatewayField>, values: Map<String, String>, enabled: Boolean, change: (String, String) -> Unit) {
    fields.filter { it.visible(values) }.forEach { field ->
        val value = values[field.key] ?: field.initial
        if (field.toggle) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(field.label); if (field.hint.isNotBlank()) Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(value == "1", { change(field.key, if (it) "1" else "0") }, enabled = enabled && !field.locked)
        } else if (field.choices.isNotEmpty()) {
            var choose by remember { mutableStateOf(false) }
            PanelSecondaryButton(onClick = { choose = true }, enabled = enabled && !field.locked, modifier = Modifier.fillMaxWidth()) { Text("${field.label}: ${gatewayChoiceLabel(value)}") }
            if (choose) PanelAlertDialog(onDismissRequest = { choose = false }, title = { Text(field.label) }, text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    field.choices.forEach { option -> TextButton(onClick = { change(field.key, option); choose = false }, modifier = Modifier.fillMaxWidth()) { Text(gatewayChoiceLabel(option)) } }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { choose = false }) { Text("Закрыть") } })
        } else OutlinedTextField(value, { change(field.key, it) }, label = { Text(field.label) }, enabled = enabled && !field.locked,
            modifier = Modifier.fillMaxWidth(), singleLine = !field.multiline,
            minLines = if (field.multiline) 2 else 1, maxLines = if (field.multiline) 5 else 1,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
            supportingText = if (field.hint.isNotBlank()) ({ Text(field.hint) }) else null)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GatewayFormDialog(form: GatewayForm, busy: Boolean, error: String?, onDismiss: () -> Unit, onSubmit: (JsonObject) -> Unit) {
    var values by remember(form) { mutableStateOf(initialValues(form.fields, form.initial)) }
    var groups by remember(form) { mutableStateOf(form.groups.associate { group -> group.key to group.rows.map { initialValues(group.fields, it) } }) }
    var validation by remember(form) { mutableStateOf<String?>(null) }
    var selectedTab by remember(form) { mutableStateOf(form.tabs.firstOrNull()?.first) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        PanelSurfaceCard(modifier = Modifier.fillMaxWidth(.95f).fillMaxHeight(.92f).imePadding()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(form.title, style = MaterialTheme.typography.titleLarge)
                if (form.tabs.isNotEmpty()) ScrollableTabRow(selectedTabIndex = form.tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0), edgePadding = 0.dp) {
                    form.tabs.forEach { (id, title) -> Tab(selected = id == selectedTab, onClick = { selectedTab = id }, text = { Text(title, maxLines = 1, softWrap = false) }) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (form.hint.isNotBlank()) Text(form.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedTab == null || selectedTab == "general") GatewayFields(form.fields, values, !busy) { key, value -> values = values + (key to value); validation = null }
                    form.groups.filter { it.visible(values) && (selectedTab == null || it.key == selectedTab) }.forEach { group ->
                        HorizontalDivider(); Text(group.title, style = MaterialTheme.typography.titleMedium)
                        if (group.hint.isNotBlank()) Text(group.hint, style = MaterialTheme.typography.bodySmall)
                        groups[group.key].orEmpty().forEachIndexed { index, row ->
                            var expanded by remember(form, group.key, index) { mutableStateOf(false) }
                            PanelCard {
                                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${index + 1}. ${row["name"] ?: row["ssid"] ?: row["description"] ?: row["target"] ?: row["public_key"]?.take(18) ?: group.title}")
                                }
                                if (expanded) {
                                    GatewayFields(group.fields, row, !busy) { key, value -> groups = groups + (group.key to groups[group.key].orEmpty().mapIndexed { i, old -> if (i == index) old + (key to value) else old }) }
                                    if (group.remove) TextButton(enabled = !busy, onClick = { groups = groups + (group.key to groups[group.key].orEmpty().filterIndexed { i, _ -> i != index }) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        if (group.add) PanelSecondaryButton(enabled = !busy, onClick = { groups = groups + (group.key to (groups[group.key].orEmpty() + initialValues(group.fields, JsonObject(emptyMap())))) }) { Text("Добавить") }
                    }
                    (validation ?: error)?.let { ErrorCard(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(onClick = {
                        val missing = form.fields.firstOrNull { it.required && it.visible(values) && values[it.key].isNullOrBlank() }
                            ?: form.groups.filter { it.visible(values) }.firstNotNullOfOrNull { group -> groups[group.key].orEmpty().firstNotNullOfOrNull { row -> group.fields.firstOrNull { it.required && it.visible(row) && row[it.key].isNullOrBlank() } } }
                        if (missing != null) validation = "Заполните поле «${missing.label}»"
                        else try { onSubmit(form.result(values, groups)) } catch (e: Exception) { validation = e.message }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Подождите…" else form.submit) }
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
                }
            }
        }
    }
}

private fun field(key: String, label: String, initial: String = "", required: Boolean = false, choices: List<String> = emptyList(), secret: Boolean = false, list: Boolean = false, hint: String = "", visible: (Map<String, String>) -> Boolean = { true }) = GatewayField(key, label, initial, choices, secret = secret, required = required, multiline = list, hint = hint, visible = visible)
private fun toggle(key: String, label: String, initial: String = "0", hint: String = "") = GatewayField(key, label, initial, toggle = true, hint = hint)
private fun choices(key: String, label: String, values: List<String>, initial: String = values.first()) = field(key, label, initial, choices = values)
private fun rows(values: List<Map<String, String>>, keys: List<String>, lists: Set<String> = emptySet(), secrets: Set<String> = emptySet()) = JsonArray(values.map { gatewayFields(it, keys, lists, secrets) })

fun gatewaySshForm(node: Gateway? = null, keys: List<String> = emptyList(), add: Boolean = false): GatewayForm {
    val raw = node?.raw ?: JsonObject(emptyMap())
    val initial = buildJsonObject {
        put("name", node?.name.orEmpty()); put("host", node?.host.orEmpty())
        put("user", raw.text("ssh_user") ?: "root"); put("port", raw.text("ssh_port") ?: "22"); put("auth_type", raw.text("ssh_auth_type") ?: "password")
    }
    val fields = (if (add) listOf(field("name", "Имя шлюза"), field("host", "Адрес роутера", required = true, hint = "192.168.1.1")) else emptyList()) + listOf(
        field("user", "Пользователь SSH", "root", required = true), field("port", "Порт SSH", "22", required = true), choices("auth_type", "Авторизация", listOf("password", "key")),
        field("password", "Пароль SSH", secret = true, hint = if (node != null) "Пусто — сохранить прежний пароль" else "", visible = { it["auth_type"] == "password" }),
        field("key_path", "SSH-ключ панели", choices = keys, required = true, visible = { it["auth_type"] == "key" }))
    return GatewayForm(if (add) "Подключить шлюз по SSH" else "SSH-доступ", fields, initial, hint = "Панель проверит OpenWrt перед сохранением доступа. SSH нужен для консоли и первоначальной настройки.", submit = if (add) "Проверить" else "Проверить и сохранить", result = { values, _ ->
        require(values["port"]?.toIntOrNull() in 1..65535) { "Порт должен быть от 1 до 65535" }
        if (node == null && values["auth_type"] == "password") require(!values["password"].isNullOrBlank()) { "Введите пароль SSH" }
        val names = listOf("user", "auth_type", "password", "key_path") + if (add) listOf("name", "host") else emptyList()
        JsonObject(gatewayFields(values, names, secrets = setOf("password", "key_path")) + ("port" to JsonPrimitive(values.getValue("port").toInt())) + if (add) mapOf("name" to JsonPrimitive(values["name"].orEmpty())) else emptyMap())
    })
}

fun gatewayAccessForm(node: Gateway? = null) = GatewayForm(if (node == null) "Подключить через API" else "Доступ к API", listOf(
    field("name", "Имя шлюза"), field("url", "Адрес HTTPS API", required = true, hint = "https://192.168.1.1/ubus"),
    field("username", "Учётная запись Bot4VPS", required = true), field("password", "Секрет API", required = true, secret = true),
    field("tls_sha256", "SHA-256 сертификата", hint = "Пусто — проверять через системное доверие")),
    initial = buildJsonObject { put("name", node?.name.orEmpty()); put("url", node?.raw?.text("api_url").orEmpty()); put("username", node?.raw?.text("api_username").orEmpty()); put("tls_sha256", node?.raw?.text("tls_sha256").orEmpty()) },
    hint = "Укажите отдельную учётную запись Bot4VPS. Root используется только при первоначальной настройке по SSH.", submit = "Проверить и сохранить", result = { values, _ ->
        val url = java.net.URI(values.getValue("url"))
        require(url.scheme == "https" && url.host != null && url.userInfo == null && url.query == null && url.fragment == null && url.path.trimEnd('/') in listOf("", "/ubus")) { "Укажите HTTPS-адрес API без пароля, параметров и фрагмента" }
        require(values["username"] != "root") { "Укажите отдельную учётную запись Bot4VPS" }
        val pin = values["tls_sha256"].orEmpty().replace(":", "").lowercase()
        require(pin.isBlank() || Regex("[a-f0-9]{64}").matches(pin)) { "SHA-256 должен содержать 64 шестнадцатеричных символа" }
        val result = gatewayFields(values, listOf("name", "url", "username", "password", "tls_sha256"))
        JsonObject(result + ("name" to JsonPrimitive(values["name"].orEmpty())))
    })

fun gatewayInterfaceForm(config: JsonObject, iface: JsonObject? = null): GatewayForm {
    val fields = listOf(field("name", "Имя подключения", required = true).copy(locked = iface != null), choices("proto", "Протокол", listOf("dhcp", "static", "pppoe", "wireguard", "none")),
        field("device", "Устройство", choices = listOf("") + config.strings("devices")),
        field("ipaddr", "IPv4-адрес", required = true, visible = { it["proto"] == "static" }), field("netmask", "Маска", "255.255.255.0", required = true, visible = { it["proto"] == "static" }), field("gateway", "Шлюз IPv4", visible = { it["proto"] == "static" }),
        field("username", "Логин PPPoE", required = true, visible = { it["proto"] == "pppoe" }), field("password", "Пароль PPPoE", secret = true, hint = "Пусто — сохранить прежний", visible = { it["proto"] == "pppoe" }),
        field("private_key", "Приватный ключ WireGuard", secret = true, hint = "Пусто — сохранить прежний", visible = { it["proto"] == "wireguard" }), field("addresses", "Адреса WireGuard (CIDR)", list = true, visible = { it["proto"] == "wireguard" }), field("listen_port", "Порт WireGuard", visible = { it["proto"] == "wireguard" }),
        field("dns", "DNS-серверы", list = true), field("metric", "Метрика"), field("mtu", "MTU"), toggle("auto", "Подключать автоматически", "1"), toggle("defaultroute", "Маршрут по умолчанию", "1"), toggle("peerdns", "Получать DNS от подключения", "1"))
    val peerFields = listOf(field("description", "Имя пира"), field("public_key", "Публичный ключ", required = true), field("endpoint_host", "Адрес узла"), field("endpoint_port", "Порт узла"), field("allowed_ips", "Разрешённые IP (CIDR)", list = true), field("persistent_keepalive", "Keepalive, секунд"), toggle("route_allowed_ips", "Создавать маршруты"), field("preshared_key", "Общий ключ", secret = true, hint = "Пусто — сохранить прежний"))
    return GatewayForm(if (iface == null) "Новое подключение" else "Настройка ${iface.text("name")}", fields, iface ?: JsonObject(emptyMap()),
        groups = listOf(GatewayRows("peers", "Пиры WireGuard", peerFields, iface?.objects("peers").orEmpty(), visible = { it["proto"] == "wireguard" })),
        hint = "Изменение интерфейса доступа проходит проверку связи. При потере связи роутер вернёт прежние настройки.", result = { values, groups -> buildJsonObject {
            put("config_hash", config.text("config_hash")); put("interface", values.getValue("name")); put("settings", gatewayInterfaceSettings(values))
            if (values["proto"] == "wireguard") put("peers", rows(groups["peers"].orEmpty(), gatewayPeerFields, setOf("allowed_ips"), setOf("preshared_key", "id")))
        } })
}

fun gatewayRoutesForm(config: JsonObject) = GatewayForm("Статические маршруты IPv4", emptyList(), groups = listOf(GatewayRows("routes", "Маршруты", listOf(
    field("interface", "Подключение", required = true, choices = config.objects("interfaces").mapNotNull { it.text("name") }), field("target", "Сеть или IP", required = true, hint = "192.168.10.0/24"),
    field("netmask", "Маска"), field("gateway", "Через шлюз"), field("metric", "Метрика"), field("table", "Таблица"), toggle("onlink", "Шлюз непосредственно доступен")), config.objects("routes"))),
    hint = "Автоматические маршруты сохранятся. В форме показан полный список статических маршрутов.", result = { _, groups -> buildJsonObject { put("config_hash", config.text("config_hash")); put("routes", rows(groups["routes"].orEmpty(), gatewayRouteFields)) } })

fun gatewayDhcpForm(config: JsonObject, iface: String): GatewayForm {
    val initial = config.objects("dhcp").find { it.text("interface") == iface } ?: buildJsonObject { put("ignore", "1") }
    return GatewayForm("DHCP · $iface", listOf(toggle("ignore", "Выключить DHCP-сервер", "1"), field("start", "Начальный адрес (смещение)", "100", required = true), field("limit", "Количество адресов", "150", required = true), field("leasetime", "Время аренды", "12h", required = true), field("dns", "DNS для DHCP-клиентов", list = true)), initial,
        hint = "В одном сегменте должен работать один DHCP-сервер. Остальные опции, статические аренды и IPv6 сохранятся.", result = { values, _ -> buildJsonObject { put("config_hash", config.text("settings_hash")); put("interface", iface); put("settings", gatewayFields(values, listOf("ignore", "start", "limit", "leasetime", "dns"), setOf("dns"))) } })
}

fun gatewayDnsForm(config: JsonObject, instance: JsonObject) = GatewayForm("DNS · ${instance.text("id")}", listOf(field("port", "Порт DNS", "53", required = true), field("cachesize", "Размер кэша", "150", required = true), field("server", "Внешние DNS-серверы", list = true, hint = "По одному на строку: 1.1.1.1, 127.0.0.1#5353, /example.org/192.168.1.2"), field("domain", "Локальный домен"), field("local", "Локальная зона"), toggle("noresolv", "Использовать только указанные DNS"), toggle("rebind_protection", "Защита от DNS rebinding", "1"), toggle("domainneeded", "Не отправлять короткие имена наружу"), toggle("boguspriv", "Не отправлять частные адреса наружу", "1")), instance,
    hint = "Порт 0 отключает только DNS. DNS подключения WAN настраивается отдельно.", result = { values, _ -> buildJsonObject { put("config_hash", config.text("settings_hash")); put("section", instance.text("id")); put("settings", gatewayFields(values, listOf("port", "cachesize", "server", "domain", "local", "noresolv", "rebind_protection", "domainneeded", "boguspriv"), setOf("server"))) } })

fun gatewayFirewallForm(config: JsonObject): GatewayForm {
    val firewall = config["firewall"] as? JsonObject ?: JsonObject(emptyMap())
    val rules = firewall.objects("rules").filter { it.text("target").isNullOrBlank() || it.text("target") in listOf("ACCEPT", "REJECT", "DROP") }
    val supportsRedirects = firewall["redirects"] is JsonArray
    val redirects = firewall.objects("redirects").filter { it.text("target").isNullOrBlank() || it.text("target") == "DNAT" }
    val zones = firewall.objects("zones").mapNotNull { it.text("name") }
    val redirectFields = listOf(field("name", "Имя перенаправления"), field("src", "Исходная зона", "wan", required = true, choices = zones), field("dest", "Зона назначения", "lan", required = true, choices = zones), choices("proto", "Протокол", listOf("tcp udp", "tcp", "udp")), field("src_dport", "Внешний порт", required = true, hint = "443 или 1000-2000"), field("dest_ip", "IP устройства назначения", required = true, hint = "192.168.1.10"), field("dest_port", "Внутренний порт", hint = "Пусто — сохранить внешний порт"), field("src_ip", "Разрешённый IP источника", hint = "Пусто — любой; можно CIDR"), toggle("enabled", "Перенаправление включено", "1"), toggle("reflection", "Доступ из LAN через внешний адрес (NAT loopback)", "1"))
    val redirectKeys = listOf("id", "name", "src", "dest", "proto", "src_dport", "dest_ip", "dest_port", "src_ip", "enabled", "reflection")
    val policies = listOf(choices("input", "Входящие", listOf("REJECT", "ACCEPT", "DROP")), choices("output", "Исходящие", listOf("ACCEPT", "REJECT", "DROP")), choices("forward", "Пересылка", listOf("REJECT", "ACCEPT", "DROP")))
    val zoneFields = policies + choices("masq", "NAT", listOf("", "0", "1")) + choices("mtu_fix", "Коррекция MSS", listOf("", "0", "1"))
    val ruleFields = listOf(field("name", "Имя правила"), field("src", "Исходная зона", "lan", choices = listOf("") + firewall.objects("zones").mapNotNull { it.text("name") }), field("dest", "Зона назначения", choices = listOf("") + firewall.objects("zones").mapNotNull { it.text("name") }), field("src_ip", "IP источника", list = true), field("dest_ip", "IP назначения", list = true), choices("proto", "Протокол", listOf("tcp", "udp", "tcp udp", "icmp", "icmpv6", "all")), field("dest_port", "Порт назначения"), choices("target", "Действие", listOf("ACCEPT", "REJECT", "DROP")), toggle("enabled", "Правило включено", "1"))
    return GatewayForm("Firewall", policies + toggle("synflood_protect", "Защита от SYN flood"), firewall["defaults"] as? JsonObject ?: JsonObject(emptyMap()), groups = listOf(GatewayRows("zones", "Зоны", zoneFields, firewall.objects("zones"), add = false, remove = false), GatewayRows("rules", "Правила трафика", ruleFields, rules), GatewayRows("redirects", "Перенаправление портов", redirectFields, redirects, add = supportsRedirects, remove = supportsRedirects, hint = if (supportsRedirects) "Внешний порт → IP устройства и внутренний порт. Специальные правила NAT сохранятся." else "Обновите интеграцию Bot4VPS на шлюзе для управления перенаправлениями.")),
        tabs = listOf("general" to "Общие настройки", "zones" to "Зоны", "rules" to "Правила трафика", "redirects" to "Перенаправление портов"),
        hint = "Специальные правила и остальные параметры firewall сохранятся. Изменения применяются с проверкой связи и откатом.", result = { values, groups -> buildJsonObject {
            put("config_hash", config.text("settings_hash")); put("settings", gatewayFields(values, listOf("input", "output", "forward", "synflood_protect")))
            put("zones", rows(groups["zones"].orEmpty(), listOf("id", "input", "output", "forward", "masq", "mtu_fix")))
            val modified = groups["rules"].orEmpty().filter { row -> val old = rules.find { it.text("id") == row["id"] }; old == null || gatewayFields(row, gatewayRuleFields, setOf("proto", "src_ip", "dest_ip")) != gatewayFields(old.mapValues { gatewayText(it.value) }, gatewayRuleFields, setOf("proto", "src_ip", "dest_ip")) }
            put("rules", rows(modified, gatewayRuleFields, setOf("proto", "src_ip", "dest_ip"), secrets = setOf("id")))
            put("deleted_rules", JsonArray(rules.mapNotNull { it.text("id") }.filter { id -> groups["rules"].orEmpty().none { it["id"] == id } }.map(::JsonPrimitive)))
            if (supportsRedirects) {
                val changed = groups["redirects"].orEmpty().filter { row -> val old = redirects.find { it.text("id") == row["id"] }; old == null || gatewayFields(row, redirectKeys, setOf("proto")) != gatewayFields(old.mapValues { gatewayText(it.value) }, redirectKeys, setOf("proto")) }
                put("redirects", rows(changed, redirectKeys, setOf("proto"), secrets = setOf("id")))
                put("deleted_redirects", JsonArray(redirects.mapNotNull { it.text("id") }.filter { id -> groups["redirects"].orEmpty().none { it["id"] == id } }.map(::JsonPrimitive)))
            }
        } })
}

fun gatewayWifiForm(config: JsonObject, radio: JsonObject): GatewayForm {
    val channels = listOf("auto") + radio.objects("frequencies").filter { !it.flag("restricted") }.mapNotNull { it.text("channel") }
    val fields = listOf(toggle("disabled", "Выключить радиомодуль"), choices("channel", "Канал", (channels + radio.text("channel").orEmpty()).distinct()), choices("country", "Страна", (listOf("", "00") + radio.objects("countries").mapNotNull { it.text("code") } + radio.text("country").orEmpty()).distinct()), choices("txpower", "Мощность, dBm", (listOf("") + radio.objects("txpowers").mapNotNull { it.text("dbm") } + radio.text("txpower").orEmpty()).distinct()), choices("htmode", "Ширина канала", (radio.strings("htmodes") + (radio.text("htmode") ?: "NOHT")).distinct()))
    val wifiFields = listOf(toggle("disabled", "Выключить сеть"), field("ssid", "Имя сети (SSID)", required = true), choices("encryption", "Защита", listOf("psk2", "psk2+ccmp", "sae", "sae-mixed", "sae+ccmp", "sae-mixed+ccmp", "psk-mixed", "none")), field("key", "Пароль Wi-Fi", secret = true, hint = "Пусто — сохранить прежний; 8–63 байта", visible = { it["encryption"] != "none" }), toggle("hidden", "Скрыть SSID"), toggle("isolate", "Изоляция клиентов"), field("network", "Сетевые подключения", list = true, hint = config.strings("networks").ifEmpty { config.objects("networks").mapNotNull { it.text("name") } }.joinToString(", ")))
    return GatewayForm("Wi-Fi · ${radio.text("name")}", fields, radio, listOf(GatewayRows("interfaces", "Беспроводные сети", wifiFields, radio.objects("interfaces"), add = false, remove = false)),
        hint = "Пустой пароль сохраняет текущий ключ. Выключение защиты удалит пароль. Доступные каналы и мощности получены от роутера.", result = { values, groups -> buildJsonObject {
            put("config_hash", config.text("config_hash")); put("radio", radio.text("name")); put("settings", gatewayWirelessChanges(values, radio, listOf("disabled", "channel", "country", "txpower", "htmode")))
            put("interfaces", JsonArray(groups["interfaces"].orEmpty().map { row -> buildJsonObject {
                val old = radio.objects("interfaces").first { it.text("id") == row["id"] }
                put("id", old.text("id")); put("settings", gatewayWirelessChanges(row, old, listOf("disabled", "ssid", "encryption", "key", "hidden", "isolate", "network")))
            } }))
        } })
}

fun gatewayChoiceLabel(value: String): String = mapOf("" to "По умолчанию", "password" to "Пароль", "key" to "SSH-ключ", "0" to "Выключено", "1" to "Включено", "dhcp" to "DHCP", "static" to "Статический IPv4", "pppoe" to "PPPoE", "wireguard" to "WireGuard", "none" to "Без защиты / протокола", "ACCEPT" to "Разрешить", "REJECT" to "Отклонить", "DROP" to "Отбросить", "auto" to "Автоматически", "tcp udp" to "TCP и UDP")[value] ?: value
