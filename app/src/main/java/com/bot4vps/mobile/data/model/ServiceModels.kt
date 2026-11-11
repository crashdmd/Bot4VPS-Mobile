package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

enum class ServiceKind(val id: String, val section: String, val title: String, val description: String) {
    WIREGUARD("wireguard", "wireguard", "WireGuard", "VPN и профили подключения"),
    DOCKER("docker", "docker", "Docker", "Контейнеры, образы и Compose"),
    XUI("3x-ui", "xui", "3x-ui", "Панель, доступ и сертификаты"),
    SCRIPTS("scripts", "files", "Скрипты", "Запуск своих сценариев на сервере");
    companion object { fun find(id: String?) = entries.find { it.id == id } }
}

enum class InstallationFilter(val title: String) {
    ALL("Все"), INSTALLED("Установлен"), ABSENT("Не установлен"), CLASSIC("Классический WG"), BLOCKED("Недоступен")
}

data class ServiceServer(
    val id: String, val name: String, val host: String, val group: String? = null,
    val online: Boolean? = null, val portOk: Boolean? = null, val status: JsonObject = JsonObject(emptyMap()), val hostIp: String? = null
) {
    val installed get() = status.flag("installed") || classic
    val classic get() = status.flag("needs_migration")
    val blocked get() = portOk == false || portOk == null && online == false
    val blockReason get() = if (online == false) "Сервер офлайн" else "SSH-порт недоступен"
    val installation get() = when { classic -> InstallationFilter.CLASSIC; installed -> InstallationFilter.INSTALLED; else -> InstallationFilter.ABSENT }
    companion object {
        fun parse(row: JsonObject, group: String? = null): ServiceServer? {
            val id = row.text("id") ?: return null
            return ServiceServer(id, row.text("name") ?: id, row.text("host").orEmpty(), group,
                (row["online"] as? JsonPrimitive)?.booleanOrNull, (row["port_ok"] as? JsonPrimitive)?.booleanOrNull,
                row["status"] as? JsonObject ?: JsonObject(emptyMap()), row.text("host_ip"))
        }
    }
}

fun filterServiceServers(rows: List<ServiceServer>, filter: InstallationFilter, search: String, group: String?): List<ServiceServer> =
    rows.filter { row -> (when (filter) {
        InstallationFilter.ALL -> true
        InstallationFilter.INSTALLED -> row.installed
        InstallationFilter.ABSENT -> !row.installed
        InstallationFilter.CLASSIC -> row.classic
        InstallationFilter.BLOCKED -> row.blocked
    }) &&
        (group == null || row.group == group) && (search.isBlank() || listOf(row.name, row.host, row.group.orEmpty()).any { it.contains(search.trim(), true) }) }

fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
fun serviceActive(state: JsonObject): Boolean = when (val active = state["active"] as? JsonPrimitive) {
    null -> false
    else -> active.booleanOrNull ?: (active.content.lowercase() in setOf("active", "running"))
}

fun retainServerOrder(previous: List<String>, incoming: List<String>): List<String> {
    val available = incoming.toSet()
    val existing = previous.toSet()
    return previous.filter { it in available } + incoming.filter { it !in existing }
}
