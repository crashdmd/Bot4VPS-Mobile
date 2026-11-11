package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import java.util.UUID

object RestoreRequests {
    fun apply(operationId: String, password: JsonElement?, protectiveBackup: Boolean): JsonObject {
        require(operationId.isNotBlank()) { "Сначала подготовьте план восстановления" }
        return buildJsonObject {
            put("prepared_operation_id", operationId)
            put("apply", true)
            put("confirm", true)
            put("request_id", UUID.randomUUID().toString())
            if (password != null && password != JsonNull) put("password", password)
            if (!protectiveBackup) put("confirm_without_protective", true)
        }
    }
}
