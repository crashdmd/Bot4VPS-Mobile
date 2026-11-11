package com.bot4vps.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MeResponse(
    @SerialName("auth_enabled") val authEnabled: Boolean = false,
    val user: String? = null,
    val theme: String? = null,
    @SerialName("mobile_authenticated") val mobileAuthenticated: Boolean = false
) {
    val authenticated: Boolean get() = mobileAuthenticated
    val username: String? get() = user
}

@Serializable
data class ServerSummary(
    val id: String,
    val name: String? = null,
    val host: String? = null,
    @SerialName("host_ip") val hostIp: String? = null,
    val port: Int? = 22,
    val user: String? = null,
    val group: String? = null,
    @SerialName("auth_type") val authType: String? = null,
    val online: Boolean? = null,
    @SerialName("ssh_error") val sshError: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val uptime: String? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Double? = null,
    @SerialName("ssl_status") val sslStatus: String? = null,
    @SerialName("ssl_days_left") val sslDaysLeft: Int? = null,
    @SerialName("certificate_check") val certificateCheck: Boolean? = null,
    @SerialName("has_running") val hasRunning: Boolean = false,
    @SerialName("queue_len") val queueLen: Int = 0,
    @SerialName("running_task_id") val runningTaskId: String? = null
)

@Serializable
data class ServersResponse(val servers: List<ServerSummary> = emptyList())

@Serializable
data class ServerDetailResponse(
    val server: ServerCore? = null,
    @SerialName("key_exists") val keyExists: Boolean? = null,
    val monitor: JsonElement? = null,
    @SerialName("running_task") val runningTask: TaskSummary? = null,
    @SerialName("queue_state") val queueState: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val queue: List<TaskSummary> = emptyList()
)

@Serializable
data class ServerCore(
    val id: String,
    val name: String? = null,
    val group: String? = null,
    val host: String? = null,
    val port: Int? = 22,
    val user: String? = null,
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("key_path") val keyPath: String? = null,
    @SerialName("ssl_host") val sslHost: String? = null,
    @SerialName("certificate_check") val certificateCheck: Boolean? = null
)

@Serializable
data class ProbeResponse(
    val info: ProbeInfo? = null,
    @SerialName("ssh_error_human") val sshErrorHuman: String? = null,
    @SerialName("host_key_mismatch") val hostKeyMismatch: Boolean = false,
    val error: String? = null
)

@Serializable
data class ProbeInfo(
    val ssh: Boolean = false, val network: String? = null,
    val hostname: String? = null, val os: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    val kernel: String? = null, val arch: String? = null,
    @SerialName("ssh_error") val sshError: String? = null
)

@Serializable
data class ServerMetrics(
    val ok: Boolean = false,
    val cpu: Double? = null,
    @SerialName("ram_pct") val ramPct: Double? = null,
    val ram: String? = null,
    @SerialName("disk_pct") val diskPct: Double? = null,
    val disk: String? = null,
    val load: String? = null,
    val uptime: String? = null,
    val error: String? = null
)

@Serializable
data class ServerPing(val ok: Boolean = false, val ms: Double? = null, val error: String? = null) {
    val latencyMs: Double? get() = ms?.takeIf { ok && it.isFinite() && it >= 0 }
}

@Serializable
data class ActionResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val detail: String? = null,
    val error: String? = null,
    val online: Boolean? = null
)

@Serializable
data class ExecRequest(val command: String, val session: Boolean = false)

@Serializable
data class ExecResponse(
    val ok: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null
)

@Serializable
data class TaskResult(
    val success: Boolean? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val output: String? = null,
    val error: String? = null
)

@Serializable
data class TaskSummary(
    val id: String,
    val name: String? = null,
    val status: String = "queued",
    val emoji: String? = null,
    val kind: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    val duration: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("is_done") val isDone: Boolean = false,
    val error: String? = null,
    @SerialName("output_lines") val outputLines: List<String> = emptyList(),
    val result: TaskResult? = null
)

@Serializable
data class TasksHistoryResponse(val tasks: List<TaskSummary> = emptyList())

@Serializable
data class ScriptInfo(
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val version: String? = null,
    val content: String? = null,
    val params: List<ScriptParam> = emptyList()
)

@Serializable
data class ScriptParam(
    val name: String,
    val type: String = "text",
    val label: String? = null,
    val condition: String? = null,
    val options: List<ScriptOption> = emptyList()
)

@Serializable
data class ScriptOption(val value: String, val label: String = value)

@Serializable
data class ScriptsResponse(
    val scripts: List<ScriptInfo> = emptyList(),
    val cwd: String? = null
)

@Serializable
data class EnqueueRequest(
    @SerialName("script_name") val scriptName: String,
    @SerialName("server_id") val serverId: String,
    val values: Map<String, String> = emptyMap()
)

@Serializable
data class EnqueueResponse(
    val ok: Boolean = false,
    @SerialName("task_id") val taskId: String? = null,
    val id: String? = null,
    val task: TaskSummary? = null,
    val position: Int? = null,
    val ahead: Int? = null,
    val detail: String? = null,
    val error: String? = null
)

@Serializable
data class SummaryResponse(
    val ok: Boolean = false,
    val servers: Int = 0,
    @SerialName("running_tasks") val runningTasks: Int = 0,
    @SerialName("queued_tasks") val queuedTasks: Int = 0,
    @SerialName("active_queues") val activeQueues: Int = 0
)
