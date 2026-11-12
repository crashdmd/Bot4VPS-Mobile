package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.errorText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

fun canTestTelegramDelivery(status: JsonObject?): Boolean = status?.flag("enabled") == true &&
    (status["status"] as? JsonObject)?.text("state") == "running"

data class TelegramDeliveryState(val running: Boolean = false, val message: String? = null, val error: String? = null)

class TelegramDeliveryController(private val scope: CoroutineScope, private val status: () -> JsonObject?,
    private val sendTest: suspend () -> JsonObject) {
    private val mutable = MutableStateFlow(TelegramDeliveryState())
    val state = mutable.asStateFlow()
    fun check() {
        if (state.value.running || !canTestTelegramDelivery(status())) return
        mutable.value = TelegramDeliveryState(running = true)
        scope.launch {
            try {
                val response = sendTest()
                val health = response["health"] as? JsonObject ?: response
                mutable.value = if (health.flag("ok") && health.text("code") == "OK")
                    TelegramDeliveryState(message = "Тестовое сообщение отправлено")
                else TelegramDeliveryState(error = health.text("reason")?.takeIf { it.isNotBlank() }
                    ?: response.text("error")?.takeIf { it.isNotBlank() } ?: "Панель не подтвердила отправку тестового сообщения")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = TelegramDeliveryState(error = errorText(e)) }
            finally { mutable.update { it.copy(running = false) } }
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun consumeMessage() { mutable.update { it.copy(message = null) } }
}
