package com.bot4vps.mobile.data.model

import java.net.IDN
import java.util.Locale
import kotlinx.serialization.json.*

/** Infer a certificate hostname without resolving it or mistaking an IP for a domain. */
fun certificateDomainFromHost(host: String?): String? {
    val value = host?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
    if (value.any { it in ":/[]" } || value.all { it.isDigit() || it == '.' }) return null
    val ascii = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull() ?: return null
    val labels = ascii.split('.')
    if (ascii.length > 253 || labels.size < 2 || labels.last().all(Char::isDigit)) return null
    if (labels.any { it.isEmpty() || it.length > 63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) return null
    return ascii
}

fun updateServerCreateField(current: JsonObject, name: String, value: JsonElement?): JsonObject {
    val next = current.toMutableMap().apply { if (value == null) remove(name) else put(name, value) }
    if (name == "host") {
        val existing = current.text("ssl_host")
        if (existing.isNullOrBlank() || existing == certificateDomainFromHost(current.text("host"))) {
            certificateDomainFromHost((value as? JsonPrimitive)?.contentOrNull)?.let { next["ssl_host"] = JsonPrimitive(it) }
                ?: next.remove("ssl_host")
        }
    }
    return JsonObject(next)
}

fun serverCreatePayload(draft: JsonObject): JsonObject = JsonObject(draft + mapOf(
    "test" to JsonPrimitive(false),
    "certificate_check" to JsonPrimitive(draft.flag("certificate_check")),
    "ssl_host" to (if (draft.flag("certificate_check")) draft["ssl_host"] ?: JsonNull else JsonNull),
    "key_path" to (if (draft.text("auth_type") == "key") draft["key_path"] ?: JsonNull else JsonNull)
))
