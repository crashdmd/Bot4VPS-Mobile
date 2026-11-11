package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

fun phoneEventCategory(event: JsonObject): String = when (event.text("type")) {
    "server" -> "online"
    "task" -> "tasks"
    "service" -> "services"
    "ssl" -> "ssl"
    "update" -> "updates"
    "backup" -> "backups"
    else -> "system"
}

/** Server event IDs, independent of Telegram delivery/read flags and timers. */
class PhoneEventTracker(initial: Set<String>?) {
    private var initialized = initial != null
    private val seen = initial.orEmpty().toMutableSet()
    fun knows(id: String) = id in seen
    fun accept(events: List<JsonObject>): List<JsonObject> {
        val fresh = events.filter { it.text("id")?.let { id -> seen.add(id) } == true }
        if (!initialized) { initialized = true; return emptyList() }
        return fresh.sortedBy { it.number("ts_epoch") ?: 0.0 }
    }
    fun checkpoint(recent: List<JsonObject>): Set<String> = recent.mapNotNull { it.text("id") }.toSet().also {
        seen.clear(); seen.addAll(it)
    }
}
