package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

data class Gateway(val raw: JsonObject) {
    val id get() = raw.text("id").orEmpty()
    val name get() = raw.text("name") ?: "OpenWrt"
    val host get() = raw.text("host").orEmpty()
    val monitor get() = raw["monitor"] as? JsonObject ?: JsonObject(emptyMap())
    val online get() = monitor.flag("ok") && !monitor.flag("stale")
    val controls get() = ((monitor["capabilities"] as? JsonObject)?.get("controls") as? JsonObject)?.flag("available") == true && online
    val services get() = monitor.objects("services")
}

data class GatewayInventory(val enabled: Boolean = false, val gateways: List<Gateway> = emptyList(), val error: String? = null)

fun decodeGateways(value: JsonObject): GatewayInventory {
    val enabled = value.flag("enabled")
    return GatewayInventory(enabled, if (enabled) value.objects("gateways").filter { !it.text("id").isNullOrBlank() }.map(::Gateway) else emptyList())
}

fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonObject.reservationMacs(): List<String> = text("mac")?.split(Regex("[\\s,]+"))
    ?.filter(String::isNotBlank) ?: strings("mac")

fun dhcpReservationForLease(ip: String, mac: String, reservations: List<JsonObject>): JsonObject? =
    reservations.firstOrNull { reservation ->
        val macs = reservation.reservationMacs()
        ip.isNotBlank() && reservation.text("ip") == ip &&
            (macs.isEmpty() || macs.any { it.equals(mac, ignoreCase = true) })
    }
fun gatewayList(value: String) = JsonArray(value.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.map(::JsonPrimitive))
fun gatewayText(value: JsonElement?): String = when (value) { is JsonArray -> value.joinToString("\n") { gatewayText(it) }; is JsonPrimitive -> value.contentOrNull.orEmpty(); else -> "" }

/** Explicit allowlists keep UCI metadata and unchanged secrets out of mutations. */
fun gatewayFields(values: Map<String, String>, keys: List<String>, lists: Set<String> = emptySet(), secrets: Set<String> = emptySet()): JsonObject = buildJsonObject {
    keys.forEach { key -> values[key]?.let { value ->
        if (key !in secrets || value.isNotBlank()) put(key, if (key in lists) gatewayList(value) else if (value.isBlank()) JsonNull else JsonPrimitive(value))
    } }
}

val gatewayInterfaceFields = listOf("proto", "device", "ipaddr", "netmask", "gateway", "dns", "metric", "mtu", "auto", "defaultroute", "peerdns", "username", "password", "private_key", "addresses", "listen_port")
val gatewayPeerFields = listOf("id", "public_key", "endpoint_host", "endpoint_port", "allowed_ips", "persistent_keepalive", "route_allowed_ips", "description", "preshared_key")
val gatewayRouteFields = listOf("interface", "target", "netmask", "gateway", "metric", "table", "onlink")
val gatewayRuleFields = listOf("id", "name", "src", "dest", "src_ip", "dest_ip", "proto", "dest_port", "target", "enabled")

fun gatewayInterfaceSettings(values: Map<String, String>): JsonObject {
    val proto = values["proto"] ?: "dhcp"
    val keys = listOf("proto", "device", "dns", "metric", "mtu", "auto", "defaultroute", "peerdns") + when (proto) {
        "static" -> listOf("ipaddr", "netmask", "gateway")
        "pppoe" -> listOf("username", "password")
        "wireguard" -> listOf("private_key", "addresses", "listen_port")
        else -> emptyList()
    }
    return gatewayFields(values, keys, setOf("dns", "addresses"), setOf("password", "private_key"))
}

fun gatewayWirelessChanges(current: Map<String, String>, original: JsonObject, keys: List<String>): JsonObject = buildJsonObject {
    keys.forEach { key -> current[key]?.let { value ->
        if (key == "key" && value.isBlank()) return@let
        val element = if (key == "network") gatewayList(value) else if (value.isBlank() && key in setOf("country", "txpower")) JsonNull else JsonPrimitive(value)
        if (element != original[key] && !(element is JsonNull && original[key] == null)) put(key, element)
    } }
    if (current["encryption"] == "none" && original.text("encryption") != "none") put("key", JsonNull)
}

fun gatewayTaskFinished(task: JsonObject) = task.text("status") in setOf("success", "success_warn", "failed", "cancelled", "done", "completed")
fun gatewayTaskSucceeded(task: JsonObject) = task.flag("success") || task.text("status") in setOf("success", "success_warn") || (task["result"] as? JsonObject)?.flag("success") == true
