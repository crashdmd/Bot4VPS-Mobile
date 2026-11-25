package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import kotlin.math.abs

data class WifiSegment(val center: Double, val width: Double, val known: Boolean)
data class WifiNetwork(val id: String, val ssid: String, val bssid: String, val channel: Int?, val frequency: Double?, val signal: Double?, val protected: Boolean, val segments: List<WifiSegment>)
data class WifiChannel(val frequency: Double, val channel: Int?, val busy: Double?, val noise: Double?, val current: Boolean)
data class WifiScan(val networks: List<WifiNetwork>, val channels: List<WifiChannel>, val currentFrequency: Double?)

fun wifiFrequency(channel: Int, band: String): Double? = when (band) {
    "2g" -> if (channel in 1..14) (if (channel == 14) 2484 else 2407 + channel * 5).toDouble() else null
    "5g" -> if (channel in 1..196) ((if (channel >= 182) 4000 else 5000) + channel * 5).toDouble() else null
    "6g" -> if (channel in 1..233) (if (channel == 2) 5935 else 5950 + channel * 5).toDouble() else null
    else -> null
}
private fun band(frequency: Double) = if (frequency < 3000) "2g" else if (frequency >= 5925) "6g" else "5g"
private fun channel(frequency: Double): Int? = when (frequency) {
    2484.0 -> 14; 5935.0 -> 2
    else -> ((frequency - (if (frequency < 3000) 2407 else if (frequency >= 5955) 5950 else if (frequency < 5000) 4000 else 5000)) / 5).takeIf { it % 1 == 0.0 }?.toInt()
}
private fun JsonObject.numeric(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

fun wifiScan(data: JsonObject): WifiScan {
    val radio = data["selected_radio"] as? JsonObject ?: JsonObject(emptyMap())
    val reference = data.objects("frequencies").ifEmpty { radio.objects("frequencies") }
    val radioBand = radio.text("band") ?: reference.firstOrNull()?.let { (it.numeric("mhz") ?: it.numeric("frequency"))?.let(::band) }.orEmpty()
    val networks = data.objects("networks").mapIndexed { i, row ->
        val ch = row.numeric("channel")?.toInt()
        val rowBand = row.text("band")?.let { if (it in listOf("2", "5", "6")) it + "g" else it } ?: radioBand
        val frequency = row.numeric("frequency") ?: row.numeric("mhz") ?: reference.firstOrNull { entry ->
            entry.numeric("channel")?.toInt() == ch && (entry.numeric("mhz") ?: entry.numeric("frequency"))?.let { band(it) == rowBand } == true
        }?.let { it.numeric("mhz") ?: it.numeric("frequency") } ?: ch?.let { wifiFrequency(it, rowBand) }
        val signal = row.numeric("signal")?.let { if (it in 2147483648.0..4294967295.0) it - 4294967296.0 else it }?.takeIf { it <= 0 }
        WifiNetwork("${row.text("bssid") ?: i}:$frequency", row.text("ssid").orEmpty().ifBlank { "Скрытая сеть" }, row.text("bssid").orEmpty(), ch ?: frequency?.let(::channel), frequency, signal,
            (row["encryption"] as? JsonObject)?.flag("enabled") == true, frequency?.let { wifiSegments(row, it) }.orEmpty())
    }.sortedByDescending { it.signal ?: -200.0 }
    val channels = sortedMapOf<Double, WifiChannel>()
    reference.forEach { row -> (row.numeric("mhz") ?: row.numeric("frequency"))?.let { frequency -> channels[frequency] = WifiChannel(frequency, row.numeric("channel")?.toInt() ?: channel(frequency), null, null, false) } }
    networks.forEach { row -> row.frequency?.let { frequency -> channels.putIfAbsent(frequency, WifiChannel(frequency, row.channel, null, null, false)) } }
    data.objects("survey").forEach { row -> row.numeric("frequency")?.let { frequency ->
        channels[frequency] = WifiChannel(frequency, channels[frequency]?.channel ?: channel(frequency), row.numeric("busy_pct")?.takeIf { it in 0.0..100.0 }, row.numeric("noise"), row.flag("in_use"))
    } }
    val current = channels.values.firstOrNull { it.current }?.frequency ?: radio.numeric("frequency")
        ?: (radio.numeric("runtime_channel") ?: radio.numeric("channel"))?.toInt()?.let { wifiFrequency(it, radioBand) }
    return WifiScan(networks, channels.values.toList(), current)
}

fun wifiSegments(row: JsonObject, frequency: Double): List<WifiSegment> {
    val ht = row["ht_operation"] as? JsonObject
    val offset = ht?.text("secondary_channel_offset").orEmpty().lowercase()
    var width = if (offset in listOf("above", "below")) 40.0 else 20.0
    var center = frequency + if (offset == "above") 10 else if (offset == "below") -10 else 0
    var known = ht?.numeric("channel_width") != null || offset in listOf("above", "below")
    fun operationFrequency(value: Double) = if (value > 1000) value else wifiFrequency(value.toInt(), band(frequency)) ?: frequency
    for (key in listOf("vht_operation", "he_operation", "eht_operation")) {
        val operation = row[key] as? JsonObject ?: continue
        val candidate = operation.numeric("channel_width") ?: continue
        if (candidate == 20.0 && width == 20.0) known = true
        if (candidate <= if (key == "vht_operation") 40 else 20) continue
        val first = operation.numeric("center_freq_1") ?: continue
        val second = operation.numeric("center_freq_2")
        if (candidate == 8080.0 && second != null) return listOf(first, second).map { WifiSegment(operationFrequency(it), 80.0, true) }
        if (candidate !in listOf(40.0, 80.0, 160.0, 320.0)) continue
        width = candidate; center = operationFrequency(first); known = true
        if ((candidate == 160.0 || candidate == 320.0) && second != null && second > 0) {
            val other = operationFrequency(second)
            if (candidate == 320.0 || abs(other - center) == 40.0) center = other
        }
        if (candidate == 80.0 && second != null && second != 0.0) {
            val other = operationFrequency(second); val distance = abs(other - center)
            if (distance == 40.0) { width = 160.0; center = other }
            else if (distance > 40.0) return listOf(center, other).map { WifiSegment(it, 80.0, true) }
        }
    }
    return listOf(WifiSegment(center, width, known))
}
