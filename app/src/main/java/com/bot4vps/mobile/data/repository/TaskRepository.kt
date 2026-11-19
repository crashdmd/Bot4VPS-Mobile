package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.local.PreferencesManager
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.di.ApiFactory
import com.bot4vps.mobile.data.api.ApiError
import retrofit2.HttpException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

fun taskNotFound(error: Throwable): Boolean = when (error) {
    is ApiError -> error.status == 404
    is HttpException -> error.code() == 404
    else -> false
}

suspend fun observeTask(current: TaskSummary, fetch: suspend (String) -> TaskSummary): TaskSummary {
    if (!current.canPoll) return current
    return try { fetch(current.id) }
    catch (error: Exception) { if (taskNotFound(error)) current.missing() else throw error }
}

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
    suspend fun observe(current: TaskSummary): TaskSummary = observeTask(current, ::task)
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
