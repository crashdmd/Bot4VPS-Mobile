package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

data class NotificationCategory(val name: String, val title: String, val description: String, val monitor: Boolean = false,
    val phoneDescription: String = description)
val notificationCategories = listOf(
    NotificationCategory("online", "Онлайн и офлайн", "Проверять доступность и сообщать, когда сервер пропал или снова отвечает.", true,
        "Сервер стал недоступен или снова отвечает."),
    NotificationCategory("tasks", "Задачи", "Запуск, завершение, ошибка и отмена задач."),
    NotificationCategory("services", "Сервисы", "Установка, удаление и обновление сервисов."),
    NotificationCategory("ssl", "Сертификаты SSL", "Проверять сроки сертификатов и сообщать об истечении и обновлении.", true,
        "Истечение срока, обновление сертификата и ошибки проверки SSL."),
    NotificationCategory("updates", "Обновления панели", "Новая версия и результат установки."),
    NotificationCategory("backups", "Резервные копии", "Создание копий, восстановление и ошибки архивов. Подробные правила оповещений задаются для каждой цели копирования."),
    NotificationCategory("system", "Системные события", "События панели, базы данных, конфига, SSH-ключей и скриптов.")
)
val sslIntervals = linkedMapOf(60 to "Каждый час", 360 to "Каждые 6 часов", 720 to "Каждые 12 часов", 1440 to "Раз в сутки", 4320 to "Раз в 3 суток", 10080 to "Раз в неделю")

data class NotificationSettings(val monitor: JsonObject, val categories: JsonObject, val telegramChannel: Boolean, val telegramIssue: String?) {
    fun enabled(category: NotificationCategory): Boolean = if (category.monitor)
        (monitor[category.name] as? JsonObject)?.flag("enabled") == true else categories[category.name] != JsonPrimitive(false)
    fun interval(name: String): Int = (monitor[name] as? JsonObject)?.number("interval")?.toInt() ?: if (name == "ssl") 1440 else 5
    fun withEnabled(category: NotificationCategory, enabled: Boolean): NotificationSettings = if (category.monitor) {
        val config = monitor[category.name] as? JsonObject ?: JsonObject(emptyMap())
        copy(monitor = JsonObject(monitor + (category.name to JsonObject(config + ("enabled" to JsonPrimitive(enabled))))))
    } else copy(categories = JsonObject(categories + (category.name to JsonPrimitive(enabled))))
}

fun telegramDeliveryIssue(data: JsonObject?): String? {
    if (data == null) return null
    val status = data["status"] as? JsonObject
    if (data["enabled"] == JsonPrimitive(false) || status?.text("state") == "disabled") return "Telegram выключен"
    if (status?.text("state") != "running") return status?.text("error") ?: "Бот не запущен"
    val health = data["health"] as? JsonObject
    if (health != null && health["ok"] == JsonPrimitive(false) && health.text("code") in setOf("INVALID_TOKEN", "API_UNAVAILABLE", "CHAT_UNAVAILABLE", "BOT_BLOCKED", "SEND_ERROR", "API_ERROR"))
        return "Последняя проверка Telegram: ${health.text("reason") ?: "ошибка доставки"}"
    return null
}
