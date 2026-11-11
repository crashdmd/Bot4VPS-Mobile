package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun auditTime(seconds: Long?): String = seconds?.let {
    runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) }.getOrNull()
} ?: "—"

fun auditDuration(seconds: Double?): String? {
    if (seconds == null || !seconds.isFinite() || seconds < 0) return null
    val n = seconds.toLong()
    return when { n < 60 -> "$n с"; n < 3600 -> "${n / 60} мин"; else -> "${n / 3600} ч ${n % 3600 / 60} мин" }
}

fun auditResult(record: JsonObject): String = when(record.text("status")) {
    "incomplete" -> "incomplete"
    "waiting_rule_selection" -> "awaiting_rule_selection"
    else -> record.text("result") ?: "unknown"
}

fun auditResultLabel(result: String): String = when(result) {
    "ok", "success", "completed" -> "Успешно"
    "failed", "FAILED" -> "Ошибка"
    "denied" -> "Отказано"
    "cancelled" -> "Отменено"
    "started" -> "Начато"
    "pending", "queued", "PENDING", "QUEUED" -> "В очереди"
    "running", "RUNNING" -> "Выполняется"
    "COMPLETED", "SUCCESS" -> "Успешно"
    "CANCELLED" -> "Отменено"
    "incomplete" -> "Нет записи о завершении"
    "awaiting_rule_selection" -> "Ожидается выбор правил"
    "unknown" -> "Итог неизвестен"
    else -> result
}

fun auditActor(record: JsonObject, details: Boolean = false): String {
    val actor = record["actor"] as? JsonObject ?: return "Система"
    val type = when(val raw = actor.text("type")) { "system" -> "Система"; "web" -> "Веб"; "telegram" -> "Telegram"; else -> raw ?: "Система" }
    return type + actor.text("id")?.let { ": $it" }.orEmpty() + actor.text("role")?.let { " · $it" }.orEmpty() +
        (if(details) actor.text("ip")?.let { " · $it" }.orEmpty() else "")
}

fun auditServer(record: JsonObject): String? = (record["server"] as? JsonObject)?.let { it.text("name") ?: it.text("id") }
fun JsonObject.auditStart(): Long? = (get("started_at") as? JsonPrimitive)?.longOrNull ?: (get("ts") as? JsonPrimitive)?.longOrNull

data class AuditFilters(val days: Int = 7, val from: Long? = null, val to: Long? = null,
    val actor: String? = null, val action: String? = null, val server: String? = null, val result: String? = null, val search: String = "") {
    fun query(now: Long): Map<String, List<String>> {
        val start = from ?: now - days * 86400L
        val end = to ?: now
        require(start <= end) { "Начало периода должно быть раньше конца" }
        require(search.length <= 200) { "Поиск: не больше 200 символов" }
        return buildMap {
            put("from", listOf(start.toString())); put("to", listOf(end.toString())); put("limit", listOf("50"))
            actor?.takeIf { it.isNotBlank() }?.let { value ->
                put("actor_type", listOf(value.substringBefore(':')))
                if(':' in value) put("actor_id", listOf(value.substringAfter(':')))
            }
            listOf("action" to action, "server_id" to server, "result" to result, "q" to search.trim()).forEach { (key, value) ->
                value?.takeIf { it.isNotBlank() }?.let { put(key, listOf(it)) }
            }
        }
    }
}

/** Navigation may carry an empty nullable argument; never turn it into a filter. */
fun auditInitialServer(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
fun auditNavigationServer(initial: String?, selected: String?, manuallySelected: Boolean): String? =
    auditInitialServer(initial) ?: auditInitialServer(selected).takeIf { manuallySelected }
