package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

data class ServiceReserveSettings(val enabled: Boolean, val keep: Int)

fun serviceReserveRequest(current: ServiceReserveSettings, enabled: Boolean? = null, keep: Int? = null): JsonObject {
    val selectedKeep = keep ?: current.keep
    require(selectedKeep in setOf(2, 3))
    return buildJsonObject { put("enabled", enabled ?: current.enabled); put("keep", selectedKeep) }
}

fun decodeServiceReserve(value: JsonObject): ServiceReserveSettings {
    val settings = value["settings"] as? JsonObject ?: error("Панель не вернула настройки копий сервисов")
    val enabled = (settings["enabled"] as? JsonPrimitive)?.booleanOrNull ?: error("Не удалось прочитать настройку резервирования")
    val keep = (settings["keep"] as? JsonPrimitive)?.intOrNull ?: error("Не удалось прочитать число сохраняемых версий")
    require(keep in setOf(2, 3)) { "Панель вернула неподдерживаемое число версий" }
    return ServiceReserveSettings(enabled, keep)
}

data class ServiceArchiveGroup(val service: String, val major: String?, val name: String, val entries: List<JsonObject>) {
    val bytes get() = entries.sumOf { it.number("size") ?: 0.0 }
    val busy get() = entries.any { it.flag("busy") }
    val platform get() = if (service == "3x-ui") "Linux" else "OpenWrt ${major?.takeIf { it.isNotBlank() } ?: "—"}"
    val deletionScope get() = if (service == "3x-ui") "linux" else major
    val removable get() = (service == "3x-ui" || major != null && Regex("\\d{2}|SNAPSHOT").matches(major)) && !busy
}

fun serviceArchiveGroups(rows: List<JsonObject>): List<ServiceArchiveGroup> = rows.groupBy { it.text("service").orEmpty() to it.text("openwrt_major") }.map { (key, entries) ->
    ServiceArchiveGroup(key.first, key.second, entries.first().text("name") ?: key.first, entries)
}.sortedWith(compareBy<ServiceArchiveGroup> { it.name.lowercase() }.thenBy { it.major.orEmpty() })
