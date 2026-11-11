package com.bot4vps.mobile.data.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PanelUrl {
    fun normalize(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: error("Укажите полный адрес: https://сервер:порт")
        require(url.isHttps) { "Приложению нужен HTTPS Mobile API. Привяжите телефон по QR в Web-панели." }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Логин и пароль вводятся отдельно от адреса" }
        require(url.query == null && url.fragment == null) { "Укажите адрес панели без параметров страницы" }
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
    }

    fun endpoint(base: String, path: String, query: Map<String, List<String>> = emptyMap()): HttpUrl {
        val normalized = normalize(base).toHttpUrlOrNull()!!
        val builder = normalized.newBuilder()
            .encodedPath(normalized.encodedPath + path.trimStart('/'))
        query.forEach { (name, values) -> values.forEach { builder.addQueryParameter(name, it) } }
        return builder.build()
    }
}
