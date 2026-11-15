package com.bot4vps.mobile.data.model

private val quotedName = Regex("«[^»]*»")
private val phoneRole = Regex("\\b[Гг]лавн(?:ый|ого|ому|ым|ом|ое)(?=\\s+(?:телефон|устройств))")
private val assignedRole = Regex("(?<=назначен )главным\\b|(?<=назначено )главным\\b")

/** Older panels use the previous role name; keep quoted user names intact. */
fun phoneRoleMessage(text: String): String {
    fun translate(part: String): String = assignedRole.replace(phoneRole.replace(part) { match ->
        when (match.value) {
            "Главный" -> "Основной"
            "главный" -> "основной"
            else -> match.value.replace("Главн", "Основн").replace("главн", "основн")
        }
    }, "основным")
    return buildString {
        var start = 0
        quotedName.findAll(text).forEach { match ->
            append(translate(text.substring(start, match.range.first)))
            append(match.value)
            start = match.range.last + 1
        }
        append(translate(text.substring(start)))
    }
}
