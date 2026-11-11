package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.model.text
import kotlinx.serialization.json.JsonObject

data class UpdateCheck(val state: JsonObject, val message: String?, val error: String?)

/** Checking for a version needs no restart preparation or monitor settings request. */
suspend fun checkPanelUpdate(request: suspend (String, String) -> JsonObject): UpdateCheck {
    val result = request("POST", "/api/update/check")
    val state = request("GET", "/api/update/state")
    val failure = result.text("error")?.takeIf { it.isNotBlank() }
        ?: state.text("last_check_error")?.takeIf { it.isNotBlank() }
    val available = (state["available"] as? JsonObject)?.text("version")?.takeIf { it.isNotBlank() }
    return UpdateCheck(state, if (failure != null) null else if (available != null) "Найдено обновление" else "Установлена актуальная версия", failure)
}
