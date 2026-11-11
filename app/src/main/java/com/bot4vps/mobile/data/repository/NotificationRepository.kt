package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.NativePanelClient
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(private val panel: NativePanelClient) {
    suspend fun load(): NotificationSettings = coroutineScope {
        val monitor = async { panel.checkedObject("GET", "/api/monitor/config") }
        val notify = async { panel.checkedObject("GET", "/api/notifications/categories") }
        val telegram = async {
            try { panel.checkedObject("GET", "/api/telegram/status") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        val categories = notify.await()
        NotificationSettings(monitor.await(), categories["categories"] as? JsonObject ?: JsonObject(emptyMap()),
            categories["telegram_channel"] != JsonPrimitive(false), telegramDeliveryIssue(telegram.await()))
    }
    suspend fun category(category: NotificationCategory, enabled: Boolean) = panel.checkedObject("POST",
        if (category.monitor) "/api/monitor/config" else "/api/notifications/categories",
        buildJsonObject { put("name", category.name); put("enabled", enabled) })
    suspend fun channel(enabled: Boolean) = panel.checkedObject("POST", "/api/notifications/channel", buildJsonObject { put("enabled", enabled) })
    suspend fun interval(name: String, minutes: Int) = panel.checkedObject("POST", "/api/monitor/config", buildJsonObject { put("name", name); put("interval", minutes) })
}
