package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceArchivesState(val origin: String? = null, val items: List<JsonObject> = emptyList(), val reserve: ServiceReserveSettings? = null,
    val loading: Boolean = false, val saving: Boolean = false, val inventoryError: String? = null, val settingsError: String? = null, val notice: String? = null)

/** Metadata only: archive bytes and installation bundles remain on the panel. */
@Singleton
class ServiceArchivesRepository @Inject constructor(private val gateways: GatewayRepository) {
    private val _state = MutableStateFlow(ServiceArchivesState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private suspend fun address(): String {
        val origin = gateways.address()
        if (_state.value.origin != origin) _state.value = ServiceArchivesState(origin = origin)
        return origin
    }
    suspend fun refreshSettings() = mutex.withLock {
        val origin = address()
        try {
            val settings = decodeServiceReserve(gateways.request(origin, "GET", "/api/gateways/reserve"))
            _state.update { if (it.origin == origin) it.copy(reserve = settings, settingsError = null) else it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { if (it.origin == origin) it.copy(settingsError = errorText(e)) else it } }
    }
    suspend fun refreshInventory() {
        val origin = address(); _state.update { it.copy(loading = true, notice = null) }
        try {
            val items = gateways.request(origin, "GET", "/api/files/services").objects("items")
            _state.update { if (it.origin == origin) it.copy(items = items, inventoryError = null) else it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { if (it.origin == origin) it.copy(inventoryError = errorText(e)) else it } }
        finally { _state.update { if (it.origin == origin) it.copy(loading = false) else it } }
    }
    suspend fun refresh() { refreshInventory(); refreshSettings() }
    suspend fun save(enabled: Boolean?, keep: Int?) = mutex.withLock {
        val origin = _state.value.origin ?: error("Сначала загрузите данные панели"); gateways.checkOrigin(origin); _state.update { it.copy(saving = true, notice = null) }
        try {
            val current = decodeServiceReserve(gateways.request(origin, "GET", "/api/gateways/reserve"))
            _state.update { if (it.origin == origin) it.copy(reserve = current) else it }
            val result = gateways.request(origin, "PUT", "/api/gateways/reserve", serviceReserveRequest(current, enabled, keep))
            val settings = decodeServiceReserve(result)
            _state.update { if (it.origin == origin) it.copy(reserve = settings, settingsError = null, notice = "Настройки сохранены на панели") else it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { if (it.origin == origin) it.copy(settingsError = errorText(e)) else it } }
        finally { _state.update { if (it.origin == origin) it.copy(saving = false) else it } }
    }
    suspend fun delete(id: String) { val origin = _state.value.origin ?: error("Сначала загрузите данные панели"); gateways.request(origin, "DELETE", "/api/files/services/${encodeSegment(id)}"); refreshInventory() }
    suspend fun deleteGroup(service: String, major: String) { val origin = _state.value.origin ?: error("Сначала загрузите данные панели"); gateways.request(origin, "DELETE", "/api/files/services/groups/${encodeSegment(service)}/${encodeSegment(major)}"); refreshInventory() }
    suspend fun prepareCopies(): JsonObject { val origin = _state.value.origin ?: error("Сначала загрузите данные панели"); val result = gateways.request(origin, "POST", "/api/gateways/reserve/refresh"); return result }
}
