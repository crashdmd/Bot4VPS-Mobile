package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.di.ApiFactory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val prefs: PreferencesManager,
    private val apiFactory: ApiFactory
) {
    private suspend fun api() = apiFactory.create(
        prefs.baseUrl.first() ?: error("Base URL not set")
    )

    suspend fun summary() = runCatching { api().summary() }.getOrNull()
    suspend fun getServers(group: String? = null) = api().servers(group = group).servers
    suspend fun getServer(id: String) = api().server(id)
    suspend fun getMetrics(id: String) = api().metrics(id)
    suspend fun ping(id: String) = api().ping(id)
    suspend fun probe(id: String) = api().probe(id)
    suspend fun reboot(id: String) = api().reboot(id)
    suspend fun exec(id: String, command: String) =
        api().exec(id, ExecRequest(command = command, session = false))
}
