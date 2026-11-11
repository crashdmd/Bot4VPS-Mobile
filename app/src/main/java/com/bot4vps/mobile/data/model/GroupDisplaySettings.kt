package com.bot4vps.mobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerGroup(val name: String, val ssl_monitor: Boolean = false, val servers: Int = 0)

@Serializable
data class GroupDisplaySettings(val hidden: Set<String> = emptySet()) {
    fun visible(names: List<String>): List<String> = names.distinct().filter { it !in hidden }
    fun shown(name: String, visible: Boolean) = copy(hidden = if (visible) hidden - name else hidden + name)
    fun renamed(old: String, new: String): GroupDisplaySettings {
        if (old == new) return this
        return copy(hidden = (hidden - old - new).let { if (old in hidden) it + new else it })
    }
    fun deleted(name: String) = copy(hidden = hidden - name)
}

/** Older versions also stored an order; keep visibility while dropping that field. */
private val groupDisplayJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

fun decodeGroupDisplaySettings(value: String): GroupDisplaySettings =
    groupDisplayJson.decodeFromString<GroupDisplaySettings>(value)

data class GroupMutation(val oldName: String? = null, val newName: String? = null, val deleted: String? = null)

fun normalizePanelTheme(value: String?): String = value?.takeIf { it in setOf("dark", "light", "glass") } ?: "dark"
