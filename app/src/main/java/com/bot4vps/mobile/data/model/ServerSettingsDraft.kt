package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

/** An unsaved form. Empty password means keep the stored secret. */
data class ServerSettingsDraft(
    val name: String = "", val group: String = "", val sslEnabled: Boolean = false, val sslHost: String = "",
    val host: String = "", val port: String = "22", val user: String = "root", val authType: String = "password",
    val keyPath: String = "", val password: String = ""
) {
    fun generalPatch(saved: JsonObject): JsonObject = buildJsonObject {
        if (name.trim() != saved.text("name").orEmpty()) put("name", name.trim())
        val groupChanged = group != saved.text("group").orEmpty()
        if (groupChanged) put("group", group)
        // The API derives SSL from group unless the current preference is explicit.
        if (groupChanged || sslEnabled != saved.flag("certificate_check")) put("certificate_check", sslEnabled)
        if (groupChanged || sslHost.trim() != saved.text("ssl_host").orEmpty()) put("ssl_host", sslHost.trim())
    }
    fun connectionPatch(saved: JsonObject): JsonObject = buildJsonObject {
        if (host.trim() != saved.text("host").orEmpty()) put("host", host.trim())
        if (port.toIntOrNull() != (saved["port"] as? JsonPrimitive)?.intOrNull) port.toIntOrNull()?.let { put("port", it) }
        if (user.trim() != saved.text("user").orEmpty()) put("user", user.trim())
        if (authType != saved.text("auth_type")) put("auth_type", authType)
        if (authType == "key" && keyPath != saved.text("key_path").orEmpty()) put("key_path", keyPath)
        if (password.isNotEmpty()) put("password", password)
    }
    fun generalError(): String? = when {
        name.trim().isEmpty() -> "Введите имя сервера"
        name.trim().length > 64 -> "Имя: не больше 64 символов"
        sslHost.trim().length > 255 -> "Домен: не больше 255 символов"
        sslHost.isNotBlank() && (sslHost.contains("://") || sslHost.contains('/') || sslHost.any(Char::isWhitespace)) -> "Введите домен без https:// и пути"
        else -> null
    }
    fun connectionError(): String? = when {
        host.trim().isEmpty() -> "Введите адрес сервера"
        host.contains("://") || host.contains('/') || host.any(Char::isWhitespace) -> "Введите IP-адрес или имя хоста без схемы и пути"
        port.toIntOrNull()?.let { it in 1..65535 } != true -> "SSH-порт: от 1 до 65535"
        user.trim().isEmpty() -> "Введите имя пользователя"
        authType !in setOf("password", "key") -> "Выберите способ входа"
        authType == "key" && keyPath.isBlank() -> "Выберите SSH-ключ"
        else -> null
    }
    companion object {
        fun from(server: JsonObject) = ServerSettingsDraft(
            name = server.text("name").orEmpty(), group = server.text("group").orEmpty(),
            sslEnabled = server.flag("certificate_check"), sslHost = server.text("ssl_host").orEmpty(),
            host = server.text("host").orEmpty(), port = server.text("port") ?: "22", user = server.text("user") ?: "root",
            authType = server.text("auth_type") ?: "password", keyPath = server.text("key_path").orEmpty()
        )
    }
}
