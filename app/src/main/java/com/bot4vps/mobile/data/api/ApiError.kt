package com.bot4vps.mobile.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.io.IOException

class ApiError(val status: Int, message: String) : IOException(message)

const val PANEL_CONNECT_TIMEOUT_MS = 10_000L
private const val NETWORK_HINT = "Проверьте интернет, адрес и порт подключения. " +
    "Если указан локальный адрес, подключитесь к той же сети, что и сервис, или к VPN с доступом к этой сети."

class PanelConnectionTimeout : IOException("Панель не ответила за 10 секунд.\n\n$NETWORK_HINT")

suspend fun <T> connectToPanel(block: suspend () -> T): T {
    val result = withTimeoutOrNull(PANEL_CONNECT_TIMEOUT_MS) { Result.success(block()) }
        ?: throw PanelConnectionTimeout()
    return result.getOrThrow()
}

fun errorText(error: Throwable): String {
    if (error is CancellationException) throw error
    if (error is PanelConnectionTimeout) return error.message!!
    if (error is java.net.UnknownHostException) return "Не удалось найти панель по этому адресу.\n\n$NETWORK_HINT"
    if (error is java.net.SocketTimeoutException || error is java.net.ConnectException ||
        error is java.net.NoRouteToHostException || error is java.net.SocketException)
        return "Не удалось связаться с панелью.\n\n$NETWORK_HINT"
    if (error is HttpException) {
        val detail = runCatching {
            val body = error.response()?.errorBody()?.string().orEmpty()
            val obj = Json.parseToJsonElement(body).jsonObject
            obj["detail"]?.let { value ->
                if (value is JsonPrimitive) value.content
                else if (value is JsonArray) value.joinToString("\n") { it.jsonObject["msg"]?.jsonPrimitive?.content.orEmpty() }
                else null
            }
        }.getOrNull()
        return detail ?: when (error.code()) {
            401 -> "Сессия истекла. Войдите заново."
            403 -> "Недостаточно прав для этой операции"
            429 -> "Слишком много попыток. Подождите и повторите."
            else -> "Панель ответила ошибкой ${error.code()}"
        }
    }
    return error.message ?: "Не удалось подключиться к панели"
}
