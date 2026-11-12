package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.errorText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class ServerCheckKind(val id: String, val title: String) {
    ONLINE("online", "Пинг"), SSL("ssl", "Сертификаты")
}

@Serializable
data class ServerCheckRow(
    val id: String, val name: String? = null, val status: String = "unknown",
    val online: Boolean? = null, val ms: Double? = null, val method: String = "—",
    val error: String = "", @SerialName("ssh_error") val sshError: String = "",
    @SerialName("days_left") val daysLeft: Int? = null,
    val expires: String = "", val checked: String = ""
) {
    val available: Boolean? get() = online ?: when (status) { "online" -> true; "offline" -> false; else -> null }
    val latencyMs: Double? get() = ms?.takeIf { available == true && it.isFinite() && it >= 0 }
}

@Serializable
data class ServerCheckReport(
    val ok: Boolean = false, val kind: String,
    val summary: JsonObject = JsonObject(emptyMap()), val servers: List<ServerCheckRow> = emptyList(),
    @SerialName("completed_at") val completedAt: Long? = null,
) {
    fun visibleServers(kind: ServerCheckKind): List<ServerCheckRow> =
        if (kind == ServerCheckKind.SSL) servers.filter { it.status != "skip" } else servers

    fun summaryLines(kind: ServerCheckKind): List<String> {
        val rows = visibleServers(kind)
        if (kind == ServerCheckKind.SSL) return listOf(
            "Действуют: ${rows.count { it.status == "valid" }} · Скоро истекают: ${rows.count { it.status == "warning" }}",
            "Истекли: ${rows.count { it.status == "expired" }} · Ошибки: ${rows.count { it.status == "error" }}")
        val online = rows.count { it.available == true }
        val offline = rows.count { it.available == false }
        val errors = rows.count { it.available == null && it.error.isNotBlank() }
        return listOf("В сети: $online · Офлайн: $offline",
            "Неизвестно: ${rows.size - online - offline - errors} · Ошибки: $errors")
    }

    fun sortedServers(kind: ServerCheckKind, ascending: Boolean): List<ServerCheckRow> {
        fun value(row: ServerCheckRow): Double? = if (kind == ServerCheckKind.ONLINE) row.latencyMs
            else row.daysLeft?.takeIf { row.status in setOf("valid", "warning", "expired") }?.toDouble()
        return visibleServers(kind).sortedWith(compareBy<ServerCheckRow> { value(it) == null }
            .thenComparator { a, b ->
                val left = value(a); val right = value(b)
                if (left == null || right == null) 0 else if (ascending) left.compareTo(right) else right.compareTo(left)
            }.thenBy { (it.name ?: it.id).lowercase() }.thenBy { it.id })
    }

    companion object {
        fun parse(value: JsonObject, kind: ServerCheckKind, json: Json): ServerCheckReport {
            require(value["servers"] is JsonArray) { "Панель не вернула результаты проверки" }
            return json.decodeFromJsonElement<ServerCheckReport>(value).also {
                require(it.ok && it.kind == kind.id) { "Панель вернула результат другой проверки" }
                require(it.servers.all { row -> row.id.isNotBlank() } && it.servers.map { row -> row.id }.distinct().size == it.servers.size) {
                    "Панель вернула некорректный список серверов"
                }
            }
        }
    }
}

data class ServerCheckRun(val report: ServerCheckReport? = null, val running: Boolean = false,
    val error: String? = null, val completedAt: Long? = null, val refreshing: Boolean = false,
    val resultsVisible: Boolean = false, val sortAscending: Boolean = true)
data class ServerChecksState(val selected: ServerCheckKind = ServerCheckKind.ONLINE,
    val runs: Map<ServerCheckKind, ServerCheckRun> = emptyMap()) {
    val current get() = runs[selected] ?: ServerCheckRun()
    val hasResults get() = current.report?.visibleServers(selected)?.isNotEmpty() == true
    val visibleResults get() = if (current.resultsVisible) current.report?.sortedServers(selected, current.sortAscending).orEmpty() else emptyList()
}

/** Manual checks keep independent results and refuse duplicate in-flight requests. */
class ServerChecksController(private val scope: CoroutineScope,
    private val check: suspend (ServerCheckKind) -> ServerCheckReport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val load: (suspend (ServerCheckKind) -> ServerCheckReport)? = null) {
    private val mutable = MutableStateFlow(ServerChecksState())
    val state = mutable.asStateFlow()

    fun select(kind: ServerCheckKind) {
        if (kind == state.value.selected) return
        mutable.update { it.copy(selected = kind, runs = it.runs +
            (kind to (it.runs[kind] ?: ServerCheckRun()).copy(resultsVisible = false))) }
    }
    fun hideResults() { mutable.update { it.copy(runs = it.runs.mapValues { (_, run) -> run.copy(resultsVisible = false) }) } }
    fun showResults() {
        if (state.value.hasResults && !state.value.current.running) update(state.value.selected) { it.copy(resultsVisible = true) }
    }
    fun sort() {
        if (state.value.visibleResults.isNotEmpty()) update(state.value.selected) { it.copy(sortAscending = !it.sortAscending) }
    }
    fun run() {
        val kind = state.value.selected
        if (state.value.runs[kind]?.let { it.running || it.refreshing } == true) return
        update(kind) { it.copy(running = true, error = null, resultsVisible = false) }
        scope.launch {
            try {
                val report = check(kind)
                update(kind) { it.copy(report = report, completedAt = report.completedAt ?: clock(), error = null, resultsVisible = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(kind) { it.copy(error = errorText(e)) }
            } finally {
                update(kind) { it.copy(running = false) }
            }
        }
    }
    fun refresh() {
        val read = load ?: return
        val kind = state.value.selected
        if (state.value.runs[kind]?.let { it.running || it.refreshing } == true) return
        update(kind) { it.copy(refreshing = true, error = null) }
        scope.launch {
            try {
                val report = read(kind)
                update(kind) { it.copy(report = report, completedAt = report.completedAt ?: it.completedAt, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update(kind) { it.copy(error = errorText(e)) } }
            finally { update(kind) { it.copy(refreshing = false) } }
        }
    }
    private fun update(kind: ServerCheckKind, change: (ServerCheckRun) -> ServerCheckRun) {
        mutable.update { it.copy(runs = it.runs + (kind to change(it.runs[kind] ?: ServerCheckRun()))) }
    }
}
