package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.api.errorText
import kotlinx.coroutines.CancellationException

data class DeviceRoleResult(val snapshot: SecuritySnapshot, val phones: List<ManagedPhone>, val managementAvailable: Boolean, val error: String? = null)

suspend fun loadDeviceRole(origin: String, snapshot: suspend (String) -> SecuritySnapshot,
    phones: suspend (String) -> List<ManagedPhone>): DeviceRoleResult {
    val role = snapshot(origin)
    if (!role.isPrimary || role.supported != true) return DeviceRoleResult(role, emptyList(), false)
    return try { DeviceRoleResult(role, phones(origin), true) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) {
        DeviceRoleResult(role, emptyList(), false, if (error is ApiError && error.status == 404)
            "Управление телефонами недоступно на этом сервисе" else errorText(error))
    }
}
