package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

fun phoneEventPreview(event: JsonObject, protected: Boolean): Pair<String, String> =
    if (protected) "Новое событие Bot4VPS" to "Откройте приложение для просмотра"
    else (event.text("title") ?: "Событие Bot4VPS") to event.text("message").orEmpty()

/** Notifications use typed metadata, never free-form messages, command output or secrets. */
fun phoneEventNotification(event: JsonObject): Pair<String, String> {
    val details = event["details"] as? JsonObject
    val reason = event.text("reason") ?: details?.text("reason")
    val title = when (reason) {
        "server_offline" -> "Сервер недоступен"
        "server_online" -> "Сервер снова в сети"
        "ssl_expired" -> "SSL-сертификат истёк"
        "ssl_renewed" -> "SSL-сертификат обновлён"
        "host_key_mismatch" -> "Изменился SSH-ключ сервера"
        "ssh_key_missing" -> "Не найден SSH-ключ"
        "task_queued" -> "Задача добавлена в очередь"
        "task_started" -> "Задача запущена"
        "task_finished" -> "Задача завершена"
        "task_failed" -> "Ошибка выполнения задачи"
        "task_cancelled" -> "Задача отменена"
        "service_installed" -> "Сервис установлен"
        "service_removed" -> "Сервис удалён"
        "service_update_available" -> "Доступно обновление сервиса"
        "update_available" -> "Доступно обновление Bot4VPS"
        "update_installed" -> "Bot4VPS обновлён"
        "update_failed" -> "Ошибка обновления Bot4VPS"
        "rollback_done" -> "Откат Bot4VPS завершён"
        "rollback_failed" -> "Ошибка отката Bot4VPS"
        "backup_completed" -> "Резервная копия создана"
        "backup_failed", "backup_integrity_failed" -> "Ошибка резервного копирования"
        "backup_started" -> "Резервное копирование запущено"
        "backup_cancelled" -> "Резервное копирование отменено"
        "backup_disk_warning", "backup_disk_critical", "backup_disk_emergency" -> "Не хватает места для резервных копий"
        "backup_disk_recovered" -> "Место для резервных копий освобождено"
        "restore_completed", "config_restored", "database_restored" -> "Восстановление завершено"
        "restore_failed" -> "Ошибка восстановления"
        else -> when (event.text("type")) {
            "server" -> "Событие сервера"
            "task", "script" -> "Событие задачи"
            "ssl" -> "Событие SSL-сертификата"
            "ssh", "key" -> "Событие SSH-доступа"
            "service" -> "Событие сервиса"
            "backup" -> "Событие резервного копирования"
            "update" -> "Событие обновления Bot4VPS"
            else -> "Системное событие Bot4VPS"
        }
    }
    val context = listOfNotNull(details?.text("server_name")?.let { "Сервер: $it" },
        details?.text("service_name")?.let { "Сервис: $it" },
        when (event.text("level")) { "critical" -> "Критическое событие"; "warning" -> "Предупреждение"; else -> null }).joinToString("\n")
    return title to context
}
