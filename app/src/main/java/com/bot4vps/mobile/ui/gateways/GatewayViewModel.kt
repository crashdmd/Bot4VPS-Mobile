package com.bot4vps.mobile.ui.gateways

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import androidx.compose.runtime.*
import javax.inject.Inject

data class GatewayReview(val title: String, val data: JsonObject, val applyPath: String, val cancelPath: String? = null, val method: String = "POST", val body: JsonObject? = null)

@HiltViewModel
class GatewayViewModel @Inject constructor(val repository: GatewayRepository, private val serviceArchives: com.bot4vps.mobile.data.repository.ServiceArchivesRepository) : ViewModel() {
    val inventory = repository.inventory
    val reserveState = serviceArchives.state
    var node by mutableStateOf<Gateway?>(null); private set
    var page by mutableStateOf("overview"); private set
    var content by mutableStateOf(JsonObject(emptyMap())); private set
    var traffic by mutableStateOf(emptyList<JsonObject>()); private set
    var period by mutableIntStateOf(3600); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var review by mutableStateOf<GatewayReview?>(null); private set
    var task by mutableStateOf<JsonObject?>(null); private set
    var taskMissing by mutableStateOf(false); private set
    var draft by mutableStateOf<JsonObject?>(null); private set
    var catalog by mutableStateOf<JsonObject?>(null); private set
    var reserve by mutableStateOf<JsonObject?>(null); private set
    var checkingServiceUpdates by mutableStateOf(false); private set
    private var origin: String? = null
    private var selected: String? = null
    private var taskJob: Job? = null
    private var taskFollowup: (suspend (JsonObject) -> Unit)? = null
    private var lastFollowupTask: String? = null
    private var adguardInstallTask: String? = null
    var postInstallAdguardHelp by mutableStateOf<JsonObject?>(null); private set
    fun consumeAdguardHelp() { postInstallAdguardHelp = null }
    private var pageJob: Job? = null
    val base get() = "/api/gateways/${encodeSegment(selected ?: error("Шлюз не выбран"))}"
    suspend fun call(method: String, path: String, body: JsonElement? = null, query: Map<String, List<String>> = emptyMap()): JsonObject {
        val expected = origin ?: repository.address().also { origin = it }
        return repository.request(expected, method, path, body, query)
    }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch { busy = true; error = null; notice = null
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) } finally { busy = false }
        }
    }
    fun open(id: String?) {
        if (selected == id) return
        pageJob?.cancel(); taskJob?.cancel(); selected = id; node = null; content = JsonObject(emptyMap()); task = null; taskMissing = false; page = "overview"
        refresh()
    }
    fun refresh() = act { repository.refresh(); if (selected != null && inventory.value.enabled) { loadNode(); loadPage() } }
    private suspend fun loadNode() { node = Gateway(call("GET", base)) }
    private suspend fun loadServiceUpdates(background: Boolean): JsonObject {
        if (!background) checkingServiceUpdates = true
        return try { call("GET", "$base/service-updates") } finally { if (!background) checkingServiceUpdates = false }
    }
    private suspend fun loadPage(background: Boolean = false) {
        if (page == "services") serviceArchives.refreshSettings()
        content = when (page) {
            "network", "wireless", "backups", "logs", "packages" -> call("GET", "$base/$page")
            "services" -> loadServiceUpdates(background)
            else -> JsonObject(emptyMap())
        }
        if (page == "overview") loadTraffic()

    }
    fun selectPage(value: String) {
        if (busy || review != null) return
        pageJob?.cancel(); if (page != value) content = JsonObject(emptyMap()); page = value
        pageJob = viewModelScope.launch { busy = true; error = null
            try { loadPage() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) } finally { busy = false }
        }
    }
    fun trafficPeriod(seconds: Int) = act { period = seconds; loadTraffic() }
    private suspend fun loadTraffic() { traffic = call("GET", "$base/traffic", query = mapOf("seconds" to listOf(period.toString()))).objects("traffic_history") }
    suspend fun observe() {
        repository.refresh()
        while (true) {
            delay(5000)
            if (busy) continue
            repository.refresh()
            if (selected == null || !inventory.value.enabled) continue
            try {
                node = inventory.value.gateways.find { it.id == selected } ?: node
                if (page == "overview") loadTraffic()
                if (page == "backups" || page == "services") loadPage(background = true)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
        }
    }
    suspend fun prepare(title: String, path: String, body: JsonObject = JsonObject(emptyMap()), apply: String = "$base/router-operations", cancel: String = "$base/operation-plans", method: String = "POST") {
        val result = call(method, path, body)
        val token = result.text("plan_token") ?: error("Панель не вернула план операции")
        review = GatewayReview(result.text("title") ?: title, result, apply, "$cancel/${encodeSegment(token)}")
    }
    fun networkPlan(action: String, data: JsonObject) = act { prepare("Проверка изменений", "$base/network/plan", buildJsonObject { put("action", action); put("data", data) }) }
    fun restartFirewall() = act {
        val config = call("GET", "$base/network")
        val hash = config.text("settings_hash") ?: error("Панель не вернула актуальные настройки firewall")
        prepare("Перезапуск firewall", "$base/network/plan", buildJsonObject {
            put("action", "firewall_restart")
            put("data", buildJsonObject { put("config_hash", hash) })
        })
    }
    fun confirmInstalledVersion(service: String, version: String, fingerprint: String, saved: () -> Unit) = act {
        call("POST", "$base/services/${encodeSegment(service)}/version", buildJsonObject { put("version", version); put("fingerprint", fingerprint); put("confirmed", true) })
        saved()
        loadPage()
        notice = "Установленная версия сохранена: $version"
    }
    fun serviceAction(service: String, action: String, extra: JsonObject = JsonObject(emptyMap())) = act {
        prepare("${gatewayActionLabel(action)} · ${gatewayServiceName(service)}", "$base/services/${encodeSegment(service)}/plan", JsonObject(extra + mapOf("action" to JsonPrimitive(action), "keep_backup" to JsonPrimitive(true))), "$base/operations")
    }
    fun integration() = act { prepare("Обновить интеграцию Bot4VPS", "$base/integration/plan", apply = "$base/integration", cancel = "$base/integration/plans") }
    fun deletion() = act {
        val data = call("GET", "$base/deletion-plan")
        review = GatewayReview("Удалить шлюз ${node?.name.orEmpty()}?", data, base, method = "DELETE")
    }
    fun backupPlan(action: String, id: String? = null) = act {
        prepare(if (action == "restore") "Восстановление настроек" else "Перезагрузка шлюза", "$base/backups/plan", buildJsonObject { put("action", action); id?.let { put("archive_id", it) } }, "$base/backups/apply", "$base/backups/plans")
    }
    fun confirm() = act {
        val current = review ?: return@act
        val response = call(current.method, current.applyPath, current.body ?: buildJsonObject { put("plan_token", current.data.text("plan_token")); put("confirmed", true) })
        review = null
        if (current.data.text("service") == "adguardhome" && current.data.text("action") == "install") adguardInstallTask = response.text("task_id")
        if (response.text("task_id") != null) track(response) else { notice = "Сохранено"; repository.refresh(force = true); if (selected != null) { loadNode(); loadPage() } }
    }
    fun cancelReview() = act {
        val current = review ?: return@act
        current.cancelPath?.let { call("DELETE", it) }
        review = null
    }
    fun createBackup() = act { track(call("POST", "$base/backups/create")) }
    fun finalizeBootstrap() = act { track(call("POST", "$base/bootstrap/finalize")) }
    fun uploadBackup(uri: Uri) = act {
        val expected = origin ?: repository.address().also { origin = it }; repository.checkOrigin(expected)
        val upload = repository.panel.upload(uri)
        require(upload.size == null || upload.size <= 32L * 1024 * 1024) { "Архив должен быть не больше 32 МБ" }
        repository.panel.request("POST", "$base/backups/upload", uploads = mapOf("file" to upload), baseUrl = expected)
        repository.checkOrigin(expected); loadPage(); notice = "Архив загружен"
    }
    fun downloadBackup(id: String, uri: Uri) = act {
        repository.checkOrigin(origin ?: repository.address())
        repository.panel.download("GET", "/api/files/gateway-backups/${encodeSegment(id)}/download", emptyMap(), null, uri)
        notice = "Копия сохранена"
    }
    fun deleteBackup(archive: JsonObject) {
        review = GatewayReview("Удалить копию ${archive.text("filename")}?", buildJsonObject { put("warnings", JsonArray(listOf(JsonPrimitive("Общий архив будет удалён для всех шлюзов.")))) }, "/api/files/gateway-backups/${encodeSegment(archive.text("id").orEmpty())}", method = "DELETE", body = JsonObject(emptyMap()))
    }
    fun saveAccess(body: JsonObject) = act {
        val old = node?.raw?.text("tls_sha256").orEmpty().lowercase().replace(":", "")
        val next = body.text("tls_sha256").orEmpty().lowercase().replace(":", "")
        if (old.isNotBlank() && old != next) {
            review = GatewayReview("Изменить доверие к сертификату?", buildJsonObject { put("warnings", JsonArray(listOf(JsonPrimitive("Прежний SHA-256: $old"), JsonPrimitive("Новый SHA-256: ${next.ifBlank { "системное доверие" }}")))) }, "$base/credentials", method = "PUT", body = JsonObject(body + ("tls_pin_confirmed" to JsonPrimitive(true))))
        } else { node = Gateway(call("PUT", "$base/credentials", body)); notice = "Доступ сохранён" }
    }
    fun saveSsh(body: JsonObject) = act { node = Gateway(call("PUT", "$base/ssh", body)); notice = "SSH проверен и сохранён" }
    fun loadKeys(onLoaded: (List<String>) -> Unit) = act { onLoaded(call("GET", "/api/keys").objects("keys").mapNotNull { it.text("name") ?: it.text("filename") }) }
    fun probe(body: JsonObject) = act {
        val response = call("POST", "/api/gateways/probe", body)
        require(response.text("node_type") == "openwrt") { "OpenWrt не обнаружен. Добавьте устройство в разделе «Серверы»." }
        draft = response
    }
    fun bootstrap() = act {
        val current = draft ?: return@act
        track(call("POST", "/api/gateways/bootstrap/${encodeSegment(current.text("draft_token").orEmpty())}", buildJsonObject { put("decision", "automatic") }))
        draft = null
    }
    fun discardDraft() = act {
        draft?.text("draft_token")?.let { call("POST", "/api/gateways/bootstrap/${encodeSegment(it)}", buildJsonObject { put("decision", "no") }) }
        draft = null
    }
    fun addManual(body: JsonObject, onAdded: (String) -> Unit) = act { val added = call("POST", "/api/gateways/manual", body); repository.refresh(); onAdded(added.text("id") ?: error("Нет идентификатора шлюза")) }
    fun packages(query: String, installed: Boolean, offset: Int = 0) = act { content = call("GET", "$base/packages", query = mapOf("query" to listOf(query), "installed" to listOf(installed.toString()), "offset" to listOf(offset.toString()))) }
    fun packagePlan(action: String, name: String = "") = act { prepare(if (action == "refresh") "Обновить каталог пакетов?" else "Установить $name?", "$base/packages/plan", buildJsonObject { put("action", action); put("package", name) }) }
    fun scan(radio: String, show: (JsonObject) -> Unit) = act { show(call("POST", "$base/wireless/${encodeSegment(radio)}/scan")) }
    fun adguard(show: (JsonObject) -> Unit) = act { show(call("GET", "$base/adguard")) }
    fun loadCatalog(service: String) = act { catalog = call("GET", "/api/gateways/catalog/${encodeSegment(service)}", query = mapOf("node_id" to listOf(selected.orEmpty()))) }
    fun clearCatalog() { catalog = null }
    fun installBundle(service: String, version: String) = act {
        val path = base; catalog = null
        track(call("POST", "$path/bundles", buildJsonObject { put("profile", service); put("version", version) })) { done ->
            val output = taskOutput(done)
            val bundleId = output.text("bundle_id") ?: ((done["result"] as? JsonObject)?.text("output") ?: done.text("output"))?.substringAfter("Комплект готов: ", "")?.trim()?.takeIf { it.isNotBlank() } ?: error("Не найден подготовленный комплект")
            prepare("Установка ${gatewayServiceName(service)} $version", "$path/services/$service/plan", buildJsonObject { put("action", "install"); put("bundle_id", bundleId); put("keep_backup", true) }, "$path/operations", "$path/operation-plans")
        }
    }
    fun updateService(service: String) = act {
        val path = base
        val existing = (content["services"] as? JsonObject)?.get(service) as? JsonObject
        val ready = existing?.get("plan") as? JsonObject
        if (ready != null) { review = GatewayReview("Обновление ${gatewayServiceName(service)}", ready, "$path/operations", "$path/operation-plans/${encodeSegment(ready.text("plan_token").orEmpty())}"); return@act }
        val pending = node?.raw?.objects("service_update_tasks")?.find { it.text("service") == service }
        val taskId = existing?.text("task_id") ?: pending?.text("task_id")
        val response = if (taskId != null) buildJsonObject { put("task_id", taskId) } else call("POST", "$path/services/$service/update-bundle")
        track(response) { done ->
            val output = taskOutput(done)
            if (output.flag("review_prepared")) {
                val data = call("GET", "$path/services/$service/update-plan", query = mapOf("task_id" to listOf(done.text("id").orEmpty())))
                review = GatewayReview("Обновление ${gatewayServiceName(service)}", data, "$path/operations", "$path/operation-plans/${encodeSegment(data.text("plan_token").orEmpty())}")
            } else if (output.text("bundle_id") != null) prepare("Обновление ${gatewayServiceName(service)}", "$path/services/$service/plan", buildJsonObject { put("action", "update"); put("bundle_id", output.text("bundle_id")); put("rollback_bundle_id", output.text("rollback_bundle_id")); put("keep_backup", true) }, "$path/operations", "$path/operation-plans")
        }
    }
    fun setReserve(enabled: Boolean?, keep: Int?) = act { serviceArchives.save(enabled, keep) }
    fun refreshReserve() = act { val result = call("POST", "/api/gateways/reserve/refresh"); notice = "Поставлено задач: ${result.objects("tasks").size}"; result.objects("errors").takeIf { it.isNotEmpty() }?.let { error = it.joinToString("\n") { row -> "${row.text("gateway_name")}: ${row.text("error")}" } } }
    private fun taskOutput(done: JsonObject): JsonObject = runCatching { repository.panel.json.parseToJsonElement((done["result"] as? JsonObject)?.text("output") ?: done.text("output").orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    fun track(response: JsonObject, followup: (suspend (JsonObject) -> Unit)? = null) {
        val id = response.text("task_id") ?: response.text("id") ?: return
        taskJob?.cancel(); taskMissing = false; taskFollowup = followup; task = response["task"] as? JsonObject ?: buildJsonObject { put("id", id); put("status", "queued") }
        taskJob = viewModelScope.launch {
            while (true) {
                try {
                    val done = call("GET", "/api/tasks/${encodeSegment(id)}", query = mapOf("full_output" to listOf("true"))); task = done
                    if (gatewayTaskFinished(done)) {
                        if (!gatewayTaskSucceeded(done)) error = done.text("error") ?: (done["result"] as? JsonObject)?.text("error") ?: "Операция завершилась без успеха. Проверьте журнал задачи."
                        else try {
                            repository.refresh(force = true); if (selected != null && inventory.value.gateways.any { it.id == selected }) { loadNode(); loadPage() }
                            if (lastFollowupTask != id) { followup?.invoke(done); lastFollowupTask = id }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorText(e) }
                        return@launch
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ApiError) { error = errorText(e); if (e.status == 404) { taskMissing = true; return@launch } }
                catch (e: Exception) { error = errorText(e) }
                delay(2000)
            }
        }
    }
    fun resumeTask() { task?.text("id")?.let { track(buildJsonObject { put("task_id", it) }, taskFollowup) } }
    fun dismissTask() {
        val completed = task
        val help = adguardInstallTask != null && completed?.text("id") == adguardInstallTask && completed != null && gatewayTaskSucceeded(completed) && !taskMissing
        taskJob?.cancel(); task = null; taskMissing = false
        if (help) { adguardInstallTask = null; act { postInstallAdguardHelp = call("GET", "$base/adguard") } }
    }
}

fun gatewayServiceName(id: String) = mapOf("podkop" to "Podkop", "ssclash" to "SSClash-Go", "adguardhome" to "AdGuard Home", "dnsmasq" to "dnsmasq", "firewall" to "Firewall")[id] ?: id
fun gatewayActionLabel(action: String) = mapOf("start" to "Запустить", "stop" to "Остановить", "restart" to "Перезапустить", "remove" to "Удалить", "install" to "Установить", "update" to "Обновить", "web_access" to "Изменить доступ")[action] ?: action
