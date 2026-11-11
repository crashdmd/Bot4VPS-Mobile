package com.bot4vps.mobile.data.model

import com.bot4vps.mobile.data.api.PanelUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class PanelConnection(val operation: String, val original: String, val target: String, val started: Long, val previousState: String?)
fun connectionTarget(base: String, operation: String, body: JsonObject): String = PanelUrl.normalize(base)
fun connectionStatusPath(operation: String) = if(operation.endsWith("/web/port")) "/api/settings/web/port-status" else "/api/settings/web/tls/status"
fun connectionOperation(id: String) = id == "POST /api/settings/web/port" || id.startsWith("POST /api/settings/web/tls/") && !id.endsWith("/upload")
