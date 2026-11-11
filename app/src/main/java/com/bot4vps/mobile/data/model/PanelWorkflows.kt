package com.bot4vps.mobile.data.model

/** User workflows are explicit. Contract metadata only supplies validation and transport. */
data class PanelAction(val id: String, val title: String)
data class PanelView(val id: String, val title: String, val operation: String, val fields: Map<String, String> = emptyMap())
object PanelWorkflows {
    fun enabled(id: String): Boolean {
        val path = id.substringAfter(' ')
        return listOf("/api/setup", "/api/auth", "/api/login", "/api/logout").none { path == it || path.startsWith("$it/") }
    }
    fun views(section: String): List<PanelView> = when(section) {
        "files" -> listOf(PanelView("docker", "Compose", "GET /api/files", mapOf("root" to "docker")), PanelView("keys", "SSH-ключи", "GET /api/files", mapOf("root" to "keys")), PanelView("scripts", "Скрипты", "GET /api/files", mapOf("root" to "scripts")))
        "audit" -> listOf(PanelView("records", "Действия", "GET /api/audit"), PanelView("timeline", "Хронология сервера", "GET /api/timeline/{server_id}"))
        "events" -> listOf(PanelView("events", "Журнал событий", "GET /api/events"))
        "queues" -> listOf(PanelView("queues", "Очереди серверов", "GET /api/queues"))
        else -> emptyList()
    }
    fun actions(section: String, view: String, insideProject: Boolean = false): List<PanelAction> = when(section) {
        "files" -> when(view) {
            "docker" -> if(insideProject) listOf(PanelAction("POST /api/files/docker/files", "Создать файл"), PanelAction("POST /api/files/docker/directories", "Создать папку"), PanelAction("POST /api/files/docker/upload", "Загрузить файл")) else listOf(PanelAction("POST /api/files/docker/projects", "Создать проект"), PanelAction("POST /api/files/docker/projects/upload", "Загрузить проект ZIP"))
            "keys" -> listOf(PanelAction("POST /api/keys/create", "Создать SSH-ключ"), PanelAction("POST /api/files/upload", "Загрузить ключ"))
            else -> listOf(PanelAction("POST /api/files/create", "Создать скрипт"), PanelAction("POST /api/files/upload", "Загрузить скрипт"))
        }
        "events" -> listOf(PanelAction("DELETE /api/events", "Очистить журнал"))
        else -> emptyList()
    }
    fun title(id: String, fallback: String): String = when(id) {
        "POST /api/servers" -> "Добавить сервер"
        "POST /api/auth/recover" -> "Восстановить доступ"
        "DELETE /api/events" -> "Очистить журнал событий"
        "DELETE /api/services/docker/{server_id}/ignored-stacks" -> "Вернуть проект в список"
        "POST /api/services/docker/{server_id}/ignored-stacks" -> "Скрыть проект"
        "POST /api/services/docker/{server_id}/stacks/{name}/files" -> "Редактировать файл проекта"
        "POST /api/auth/account" -> "Учётная запись"
        "POST /api/auth/password" -> "Изменить пароль"
        "POST /api/telegram/settings" -> "Настройки Telegram"
        "POST /api/telegram/health" -> "Проверить доставку в Telegram"
        "POST /api/files/write" -> "Редактировать файл"
        else -> if(id.endsWith("/restore")) "Восстановление из архива" else if(id.endsWith("/filename")) "Переименовать архив" else fallback
    }
    fun button(op: NativeOperation): String = when {
        op.download -> "Сохранить файл"
        op.body?.text("model") == "RestoreBody" -> "Подготовить план"
        op.method == "DELETE" -> "Удалить"
        op.method in setOf("PUT", "PATCH") || op.path.startsWith("/api/settings/") || op.path in setOf("/api/files/write", "/api/auth/account", "/api/telegram/settings") || op.path.endsWith("/file") -> "Сохранить"
        op.path.endsWith("/create") || op.path == "/api/servers" -> "Создать"
        op.path.contains("/upload") || op.path.endsWith("/import") || op.path.endsWith("/zip") -> "Загрузить"
        op.path.contains("/check") || op.path.endsWith("/health") -> "Проверить"
        op.path.endsWith("/cancel") -> "Отменить задачу"
        op.path.endsWith("/confirm") -> "Подтвердить код"
        op.method == "GET" -> "Обновить"
        else -> "Продолжить"
    }
    fun confirm(op: NativeOperation): Boolean = connectionOperation(op.id) || op.destructive && op.body?.text("model") != "RestoreBody" || op.path.contains("/enqueue/remove") || op.path in setOf("/api/masterkey/new", "/api/auth/disable")
}
