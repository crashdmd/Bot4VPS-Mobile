package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.JsonObject

fun phoneEventPreview(event: JsonObject, protected: Boolean): Pair<String, String> =
    if (protected) "Новое событие Bot4VPS" to "Откройте приложение для просмотра"
    else (event.text("title") ?: "Событие Bot4VPS") to event.text("message").orEmpty()
