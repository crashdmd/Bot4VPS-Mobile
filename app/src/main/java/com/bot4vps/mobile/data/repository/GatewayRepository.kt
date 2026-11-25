package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayRepository @Inject constructor(val panel: NativePanelClient, private val prefs: PreferencesManager) {
    private val state = MutableStateFlow(GatewayInventory())
    val inventory = state.asStateFlow()
    private var origin: String? = null
    private var lastRefresh = 0L
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun address() = prefs.baseUrl.first() ?: error("Сначала подключитесь к панели")
    suspend fun checkOrigin(expected: String) { check(address() == expected) { "Панель изменилась. Откройте шлюз заново." } }
    suspend fun request(expected: String, method: String, path: String, body: JsonElement? = null, query: Map<String, List<String>> = emptyMap()): JsonObject {
        checkOrigin(expected)
        val result = panel.request(method, path, body = body, query = query, baseUrl = expected) as? JsonObject ?: error("Некорректный ответ панели")
        checkOrigin(expected)
        return result
    }
    suspend fun refresh(force: Boolean = false) {
        mutex.lock()
        try {
            val expected = address()
            if (origin != expected) { origin = expected; lastRefresh = 0; state.value = GatewayInventory() }
            if (!force && System.nanoTime() - lastRefresh < 3_000_000_000L) return
            lastRefresh = System.nanoTime()
            try { state.value = decodeGateways(request(expected, "GET", "/api/gateways", query = mapOf("compact" to listOf("true")))) }
            catch (e: CancellationException) { throw e }
            catch (e: ApiError) { state.value = if (e.status == 404) GatewayInventory() else state.value.copy(error = errorText(e)) }
            catch (e: Exception) { state.value = state.value.copy(error = errorText(e)) }
        } finally { mutex.unlock() }
    }
}
