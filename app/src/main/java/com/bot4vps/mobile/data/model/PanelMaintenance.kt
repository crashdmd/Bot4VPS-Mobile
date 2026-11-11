package com.bot4vps.mobile.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val updateBusyStatuses = setOf("downloading", "installing", "rolling_back")

@Serializable
data class MaintenanceRun(val id: String, val kind: String, val target: String, val instance: String,
    val started: Long, val seenBusy: Boolean = false)

enum class MaintenancePhase { WAITING, EXECUTING, DONE, FAILED, CANCELLED }
data class MaintenanceDecision(val phase: MaintenancePhase, val message: String, val remaining: Int = 0)

fun samePanelVersion(a: String?, b: String?): Boolean {
    fun normalized(value: String?): List<Int>? {
        val parts = value?.removePrefix("v")?.removePrefix("V")?.split('.') ?: return null
        if (parts.size !in 1..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return (numbers + listOf(0, 0)).take(3)
    }
    return !a.isNullOrBlank() && !b.isNullOrBlank() && normalized(a) != null && normalized(a) == normalized(b)
}

fun maintenanceDecision(run: MaintenanceRun, panel: JsonObject, update: JsonObject): MaintenanceDecision {
    val maintenance = panel["maintenance"] as? JsonObject
    if (maintenance?.text("id") == run.id) {
        when (maintenance.text("phase")) {
            "waiting" -> return MaintenanceDecision(MaintenancePhase.WAITING, "Ожидаем завершения текущих задач", maintenance["remaining_seconds"]?.jsonPrimitive?.intOrNull ?: 0)
            "stopping" -> return MaintenanceDecision(MaintenancePhase.EXECUTING, "Останавливаем задачи перед операцией с панелью")
            "cancelled" -> return MaintenanceDecision(MaintenancePhase.CANCELLED, "Подготовка отменена")
            "failed" -> return MaintenanceDecision(MaintenancePhase.FAILED, maintenance.text("error") ?: "Операция не выполнена")
        }
    }
    val changed = panel.text("instance_id")?.let { it.isNotBlank() && run.instance.isNotBlank() && it != run.instance } == true
    if (run.kind == "restart") {
        return if (changed)
            MaintenanceDecision(MaintenancePhase.DONE, "Панель перезапущена и готова.")
        else MaintenanceDecision(MaintenancePhase.EXECUTING, "Ожидаем перезапуска панели и проверяем подключение")
    }
    val busy = update.text("status") in updateBusyStatuses
    if (update.text("status") == "failed" && (changed || run.seenBusy))
        return MaintenanceDecision(MaintenancePhase.FAILED, update.text("last_error") ?: "Операция завершилась с ошибкой")
    if (update.text("status") == "idle" && (changed || run.seenBusy) &&
        samePanelVersion(update.text("current_version"), run.target) && samePanelVersion(panel.text("version"), run.target))
        return MaintenanceDecision(MaintenancePhase.DONE, if(run.kind == "restart") "Панель перезапущена и готова." else if (run.kind == "rollback") "Откат завершён. Панель готова." else "Обновление установлено. Панель готова.")
    return MaintenanceDecision(MaintenancePhase.EXECUTING, when (update.text("status")) {
        "downloading" -> "Загружаем файлы версии"
        "installing" -> "Устанавливаем обновление и ожидаем перезапуска панели"
        "rolling_back" -> "Устанавливаем выбранную версию и ожидаем перезапуска панели"
        else -> if (busy) "Операция выполняется" else "Проверяем результат операции"
    })
}

fun maintenanceTaskWarning(preview: JsonObject): String {
    require(!preview.text("instance_id").isNullOrBlank()) { "Не удалось проверить работающие задачи" }
    val tasks = preview["tasks"] as? JsonObject ?: error("Не удалось проверить работающие задачи")
    val running = tasks["running"] as? JsonArray ?: error("Не удалось проверить работающие задачи")
    val queued = tasks["queued"]?.jsonPrimitive?.intOrNull ?: 0
    if (running.isEmpty() && queued == 0) return "Работающих задач нет."
    val lines = mutableListOf("Выполняется задач: ${running.size}. В очереди: $queued.")
    running.take(5).forEach { row -> (row as? JsonObject)?.let { lines += "• ${it.text("name")} — ${it.text("server_name")}" } }
    lines += "Дадим задачам до ${tasks["grace_seconds"]?.jsonPrimitive?.intOrNull ?: 30} секунд на завершение, затем начнём принудительную остановку. Ожидающие задачи будут отменены."
    lines += "Отмена не откатывает уже сделанные изменения на серверах. Задачи автоматически не возобновятся."
    return lines.joinToString("\n\n")
}
