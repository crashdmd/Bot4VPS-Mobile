package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramDeliveryTest {
    private fun status(enabled: Boolean = true, state: String = "running") = buildJsonObject {
        put("enabled", enabled); put("status", buildJsonObject { put("state", state) })
    }
    @Test fun onlyAnEnabledRunningBotCanSendAndDuplicateClicksSendOnce() = runTest {
        var current: JsonObject? = null
        var calls = 0
        val reply = CompletableDeferred<JsonObject>()
        val controller = TelegramDeliveryController(this, { current }) { calls++; reply.await() }
        for (data in listOf(null, status(false), status(state = "stopped"), status(state = "starting"))) {
            current = data; controller.check(); runCurrent()
            assertEquals(0, calls)
            assertFalse(controller.state.value.running)
        }
        current = status(); controller.check(); controller.check(); runCurrent()
        assertEquals(1, calls); assertTrue(controller.state.value.running)
        reply.complete(buildJsonObject { put("ok", true); put("code", "OK") }); runCurrent()
        assertEquals("Тестовое сообщение отправлено", controller.state.value.message)
        assertNull(controller.state.value.error)
        controller.consumeMessage(); assertNull(controller.state.value.message)
    }
    @Test fun http200FailurePreservesFullDiagnosticUntilAcknowledged() = runTest {
        val reason = "Бот заблокирован пользователем.\nРазблокируйте бота и отправьте /start."
        val controller = TelegramDeliveryController(this, { status() }) {
            buildJsonObject { put("health", buildJsonObject { put("ok", false); put("code", "BOT_BLOCKED"); put("reason", reason) }) }
        }
        controller.check(); runCurrent()
        assertEquals(reason, controller.state.value.error)
        assertNull(controller.state.value.message)
        assertFalse(controller.state.value.running)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(reason, controller.state.value.error)
        controller.dismissError(); assertNull(controller.state.value.error)
    }
    @Test fun transportErrorAndMalformedConfirmationAreNotSuccessAndAllowRetry() = runTest {
        var attempt = 0
        val controller = TelegramDeliveryController(this, { status() }) {
            when (++attempt) {
                1 -> throw ApiError(503, "Сервис Telegram недоступен")
                2 -> JsonObject(emptyMap())
                else -> buildJsonObject { put("health", buildJsonObject { put("ok", true); put("code", "OK") }) }
            }
        }
        controller.check(); runCurrent(); assertTrue(controller.state.value.error!!.contains("Сервис Telegram недоступен"))
        controller.check(); runCurrent(); assertNotNull(controller.state.value.error)
        controller.check(); runCurrent(); assertNull(controller.state.value.error); assertNotNull(controller.state.value.message)
    }
    @Test fun cancellationNeverReportsDeliverySuccessOrLeavesTheButtonBusy() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = TelegramDeliveryController(owner, { status() }) { awaitCancellation() }
        controller.check(); runCurrent(); owner.cancel(); runCurrent()
        assertFalse(controller.state.value.running)
        assertNull(controller.state.value.message)
        assertNull(controller.state.value.error)
    }
}
