package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

suspend fun NativePanelClient.checkedObject(method: String, path: String, body: JsonElement? = null, query: Map<String, List<String>> = emptyMap()): JsonObject {
    val response = request(method, path, query = query, body = body) as? JsonObject ?: error("Панель вернула неожиданный ответ")
    if (response["ok"] == JsonPrimitive(false) || response["success"] == JsonPrimitive(false))
        throw ApiError(400, response.text("error") ?: response.text("detail") ?: "Операция не выполнена")
    return response
}

data class ServiceDetail(val server: ServiceServer, val state: JsonObject, val images: List<JsonObject> = emptyList(), val stacks: JsonObject = JsonObject(emptyMap()))

@Singleton
class ServiceRepository @Inject constructor(private val panel: NativePanelClient) {
    suspend fun servers(kind: ServiceKind): List<ServiceServer> = coroutineScope {
        val status = async { panel.checkedObject("GET", "/api/services/${kind.id}/status") }
        val serverGroups = async { try { panel.checkedObject("GET", "/api/servers").objects("servers").associate { it.text("id").orEmpty() to it.text("group") } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() } }
        val groups = serverGroups.await()
        status.await().objects("servers").mapNotNull { ServiceServer.parse(it, groups[it.text("id")]) }
    }

    suspend fun detail(kind: ServiceKind, id: String, tab: String): ServiceDetail {
        val server = servers(kind).find { it.id == id } ?: error("Сервер не найден")
        if (server.blocked) return ServiceDetail(server, server.status)
        val base = "/api/services/${kind.id}/${encodeSegment(id)}"
        val state = panel.checkedObject("GET", "$base/state")["state"] as? JsonObject ?: error("Нет состояния сервиса")
        state.text("error")?.takeIf(String::isNotBlank)?.let { error(it) }
        if (!state.flag("installed")) return ServiceDetail(server, state)
        val images = if (kind == ServiceKind.DOCKER && tab == "images") panel.checkedObject("GET", "$base/images").objects("images") else emptyList()
        val stacks = if (kind == ServiceKind.DOCKER && tab == "compose") panel.checkedObject("GET", "$base/stacks") else JsonObject(emptyMap())
        return ServiceDetail(server, state, images, stacks)
    }

    suspend fun action(kind: ServiceKind, id: String, suffix: String, body: JsonObject? = null): JsonObject {
        val server = servers(kind).find { it.id == id } ?: error("Сервер не найден")
        require(!server.blocked) { server.blockReason }
        return panel.checkedObject("POST", "/api/services/${kind.id}/${encodeSegment(id)}/$suffix", body)
    }
}
