package com.bot4vps.mobile.data.api

import com.bot4vps.mobile.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface Bot4VpsApi {
    @GET("api/me") suspend fun me(): MeResponse

    @GET("api/summary") suspend fun summary(): SummaryResponse

    @GET("api/servers")
    suspend fun servers(
        @Query("group") group: String? = null,
        @Query("online") online: Boolean? = null
    ): ServersResponse

    @GET("api/servers/{id}") suspend fun server(@Path("id") id: String): ServerDetailResponse
    @GET("api/servers/{id}/metrics") suspend fun metrics(@Path("id") id: String): ServerMetrics
    @GET("api/servers/{id}/ping") suspend fun ping(@Path("id") id: String): ServerPing
    @GET("api/servers/{id}/probe") suspend fun probe(@Path("id") id: String): ProbeResponse
    @POST("api/servers/{id}/reboot") suspend fun reboot(@Path("id") id: String): ActionResponse
    @POST("api/servers/{id}/exec") suspend fun exec(@Path("id") id: String, @Body body: ExecRequest): ExecResponse

    @GET("api/tasks/history")
    suspend fun taskHistory(
        @Query("limit") limit: Int = 50,
        @Query("server_id") serverId: String? = null
    ): TasksHistoryResponse

    @GET("api/tasks/history/{id}") suspend fun taskHistoryItem(@Path("id") id: String): TaskSummary
    @GET("api/tasks/{id}") suspend fun task(@Path("id") id: String): TaskSummary
    @POST("api/tasks/{id}/cancel") suspend fun cancelTask(@Path("id") id: String): ActionResponse
    @DELETE("api/tasks/history/{id}") suspend fun deleteTask(@Path("id") id: String): ActionResponse
    @DELETE("api/tasks/history") suspend fun clearTaskHistory(): ActionResponse

    @GET("api/scripts") suspend fun scripts(): ScriptsResponse
    @GET("api/scripts/{name}") suspend fun script(@Path("name") name: String): ScriptInfo

    @POST("api/tasks/enqueue") suspend fun enqueue(@Body body: EnqueueRequest): EnqueueResponse
}
