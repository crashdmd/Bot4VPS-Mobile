package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

data class XuiCertificate(val domain: String, val engine: String) {
    val id get() = "$engine|$domain"
    val label get() = "$domain — ${if (engine == "acme") "acme.sh" else "certbot"}"
}

fun xuiCertificates(state: JsonObject): List<XuiCertificate> {
    val cert = state["cert"] as? JsonObject ?: return emptyList()
    val acme = (cert["domains"] as? JsonObject).orEmpty().keys.map { XuiCertificate(it, "acme") }
    val certbot = (cert["certbot_domains"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { XuiCertificate(it, "certbot") }
    return (acme + certbot).distinct()
}

private fun field(title: String, type: String = "string", default: JsonElement? = null, required: Boolean = false, extras: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
    put("title", title); put("type", type); put("required", required)
    default?.let { put("default", it) }; extras.forEach { (key, value) -> put(key, value) }
}

/** Explicit fields from the web dialogs. The open params transport never defines a user menu. */
fun xuiForm(action: String, state: JsonObject, supplied: Map<String, JsonElement>): JsonObject? {
    val restart = field("Перезапустить панель сейчас", "boolean", JsonPrimitive(true))
    val domain = field("Домен", required = true)
    val port = field("Порт выпуска", "integer", JsonPrimitive(80), true, mapOf("minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(65535)))
    val fields: Map<String, JsonObject> = when (action) {
        "set_bbr", "unit_action", "set_autostart", "fakesite_remove" -> emptyMap()
        "change_username" -> linkedMapOf("username" to field("Новый логин", required = true, extras = mapOf("minLength" to JsonPrimitive(3), "maxLength" to JsonPrimitive(32))), "restart" to restart)
        "change_password" -> linkedMapOf("password" to field("Новый пароль", required = true, extras = mapOf("minLength" to JsonPrimitive(8), "secret" to JsonPrimitive(true))), "restart" to restart)
        "change_port" -> linkedMapOf("port" to field("Новый порт", "integer", state["port"], true, mapOf("minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(65535))), "close_old_port" to field("Закрыть старый порт в firewall", "boolean", JsonPrimitive(true)), "restart" to restart)
        "change_path" -> linkedMapOf("path" to field("Новый путь", required = true, extras = mapOf("minLength" to JsonPrimitive(4), "maxLength" to JsonPrimitive(64))), "restart" to restart)
        "reset_settings", "update_geo" -> mapOf("restart" to restart)
        "update" -> mapOf("tag" to field("Версия X.Y.Z (пусто — последняя)"))
        "cert_issue_domain" -> linkedMapOf("domain" to domain, "port" to port,
            "engine" to field("Движок", default = JsonPrimitive(if ((state["cert"] as? JsonObject)?.text("panel_engine") == "certbot") "certbot" else "acme"), required = true, extras = mapOf("enum" to JsonArray(listOf(JsonPrimitive("acme"), JsonPrimitive("certbot"))))),
            "set_panel" to field("Назначить сертификат панели", "boolean", JsonPrimitive(true)))
        "cert_issue_ip" -> linkedMapOf("ip" to field("Публичный IPv4 сервера", default = supplied["ip"], required = true), "ipv6" to field("IPv6 (необязательно)"))
        "cert_renew", "cert_remove" -> {
            val certificates = xuiCertificates(state)
            mapOf("certificate" to field("Сертификат", default = certificates.firstOrNull()?.id?.let(::JsonPrimitive), required = true,
                extras = mapOf("enum" to JsonArray(certificates.map { JsonPrimitive(it.id) }))))
        }
        "fakesite_install" -> mapOf("domain" to domain)
        else -> return null
    }
    return buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(fields)); put("required", JsonArray(fields.filterValues { it.flag("required") }.keys.map(::JsonPrimitive)))
    }
}

fun xuiFormError(action: String, values: JsonObject): String? {
    fun text(key: String) = values.text(key).orEmpty().trim()
    if (action in setOf("cert_issue_domain", "fakesite_install") && !Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$").matches(text("domain"))) return "Домен: например panel.example.com"
    if (action == "change_username" && !Regex("[A-Za-z0-9._-]{3,32}").matches(text("username"))) return "Логин: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание"
    if (action == "change_path" && !Regex("[A-Za-z0-9_-]{4,64}").matches(text("path"))) return "Путь: 4–64 символа, латиница, цифры, дефис или подчёркивание"
    if (action == "change_port" && text("port") == "80") return "Порт 80 зарезервирован для выпуска сертификатов"
    if (action == "update" && text("tag").isNotBlank() && !Regex("v?\\d+\\.\\d+\\.\\d+").matches(text("tag"))) return "Версия: формат X.Y.Z"
    if (action == "cert_issue_ip") {
        val octets = text("ip").split('.')
        if (octets.size != 4 || octets.any { part -> part.isEmpty() || part.any { !it.isDigit() } || part.toIntOrNull() !in 0..255 }) return "Введите правильный IPv4"
        val ipv6 = text("ipv6")
        if (ipv6.isNotEmpty() && (!ipv6.contains(':') || !Regex("[0-9a-fA-F:.]+").matches(ipv6) || runCatching { java.net.InetAddress.getByName(ipv6) }.getOrNull() !is java.net.Inet6Address)) return "Введите правильный IPv6"
    }
    return null
}

fun xuiParams(action: String, values: JsonObject, state: JsonObject): JsonObject {
    val cleaned = JsonObject(values.filterValues { it != JsonNull && !(it is JsonPrimitive && it.isString && it.content.isBlank()) })
    if (action !in setOf("cert_renew", "cert_remove")) return cleaned
    val selected = xuiCertificates(state).find { it.id == values.text("certificate") } ?: error("Сертификат больше недоступен. Обновите список.")
    return buildJsonObject { put("domain", selected.domain); put("engine", selected.engine) }
}

fun xuiActionDescription(action: String, state: JsonObject): String = when (action) {
    "set_bbr" -> if ((state["bbr"] as? JsonObject)?.flag("enabled") != true) "Включит алгоритм TCP BBR. Текущий алгоритм сохранится и вернётся при отключении." else if ((state["bbr"] as? JsonObject)?.flag("managed") != true) "BBR включён вне Bot4VPS. Исходные значения неизвестны: будут восстановлены стандартные fq_cubic/cubic." else "Вернёт алгоритм, который использовался до включения BBR."
    "cert_issue_domain" -> "A-запись домена должна указывать на сервер. Порт выпуска должен быть доступен из интернета. Порт 80 подготовим на время выпуска и затем восстановим. Назначение панели включает HTTPS и перезапускает её."
    "cert_issue_ip" -> "Короткоживущий сертификат Let's Encrypt (~6 дней), автоматически обновляется acme.sh. Порт 80 должен быть доступен из интернета. Сертификат будет назначен панели."
    "cert_renew" -> "Перевыпустит выбранный сертификат его движком. Хук обновит файлы и перезапустит панель. Порт 80 подготовим на время проверки и затем восстановим."
    "cert_remove" -> "Сертификат будет отозван в Let's Encrypt и удалён с сервера его движком. Если панель использовала его, пути сбросятся и панель перезапустится с HTTP."
    "fakesite_install" -> "A-запись домена должна указывать на этот сервер. Будет создан локальный HTTPS-сайт SelfSNI с Let's Encrypt. Инбаунды Reality не изменяются. После установки настройте вручную: Dest = 127.0.0.1:9000, Xver = 1, SNI = ваш домен."
    "fakesite_remove" -> "Удалит конфигурацию SelfSNI и восстановит прежний сайт, если он не менялся после установки. Сертификат Let's Encrypt останется на сервере."
    "reset_settings" -> "Настройки панели будут сброшены. Логин и пароль станут admin/admin — смените пароль после сброса."
    "change_path" -> "Старые ссылки перестанут работать. Новый адрес появится в карточке панели."
    "update_geo" -> "Загрузит свежие GeoIP/GeoSite. После перезапуска Xray подхватит новые правила маршрутизации."
    "update" -> "База и настройки /etc/x-ui сохраняются. Скачивание использует соединение сервера."
    else -> "Изменения применятся после перезапуска панели."
}
