package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.di.ApiFactory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    private val prefs: PreferencesManager,
    private val apiFactory: ApiFactory
) {
    private suspend fun api() = apiFactory.create(
        prefs.baseUrl.first() ?: error("Base URL not set")
    )

    suspend fun history(limit: Int = 50, serverId: String? = null): List<TaskSummary> =
        api().taskHistory(limit, serverId).tasks

    suspend fun task(id: String): TaskSummary = api().task(id)
    suspend fun cancel(id: String) = api().cancelTask(id)
    suspend fun delete(id: String) = api().deleteTask(id)
    suspend fun clearHistory() {
        val response = api().clearTaskHistory()
        check(response.ok) { response.error ?: response.detail ?: response.message ?: "Не удалось очистить историю задач" }
    }

    suspend fun scripts(): List<ScriptInfo> =
        api().scripts().scripts
    suspend fun script(name: String) = api().script(name)

    suspend fun enqueue(scriptName: String, serverId: String, values: Map<String, String> = emptyMap()) =
        api().enqueue(EnqueueRequest(scriptName, serverId, values))
}
