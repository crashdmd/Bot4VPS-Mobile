package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.roundToLong

data class HistoryPoint(val time: Long, val value: Double?)
data class HistorySeries(val key: String, val label: String, val points: List<HistoryPoint>,
    val percent: Boolean = true, val disk: Boolean = false)

data class HistoryWindow(val from: Long, val to: Long) {
    init { require(from < to) }
    val span get() = to - from
    fun transform(panFraction: Float = 0f, zoom: Float = 1f, anchor: Float = .5f,
        now: Long): HistoryWindow {
        val maxSpan = minOf(730L * 86400, now.coerceAtLeast(1))
        val minSpan = minOf(60L, maxSpan)
        val factor = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
        val length = (span / factor.toDouble()).roundToLong().coerceIn(minSpan, maxSpan)
        val center = from + span * anchor.coerceIn(0f, 1f).toDouble()
        val left = (center - length * anchor.coerceIn(0f, 1f) - panFraction * length)
            .roundToLong().coerceIn(0L, (now - length).coerceAtLeast(0))
        return HistoryWindow(left, left + length)
    }
}

data class MetricHistory(val window: HistoryWindow, val series: List<HistorySeries>,
    val gaps: List<Pair<Long, Long>> = emptyList(), val diskGaps: List<Pair<Long, Long>> = emptyList(),
    val rawValues: Map<Long, JsonObject> = emptyMap(), val diskLabels: Map<Long, String> = emptyMap(),
    val hourly: Boolean = false) {
    fun nearestTime(time: Long, visible: HistoryWindow): Long? {
        // Never snap a touch in a missing interval to an unrelated successful sample.
        if (gaps.any { time > it.first && time < it.second }) return time
        return series.flatMap { it.points }.asSequence().map { it.time }
            .filter { it in visible.from..visible.to }.minByOrNull { abs(it - time) }
    }
    fun value(series: HistorySeries, time: Long): Double? {
        val missing = if (series.disk) diskGaps else gaps
        if (missing.any { time > it.first && time < it.second }) return null
        // Every legend row belongs to this exact sample/bucket, with no interpolation.
        return series.points.find { it.time == time }?.value
    }
}

fun decodeMetricHistory(data: JsonObject, fallback: HistoryWindow): MetricHistory {
    fun points(value: JsonElement?): List<HistoryPoint> = (value as? JsonArray).orEmpty().mapNotNull { row ->
        val pair = row as? JsonArray ?: return@mapNotNull null
        val time = (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val value = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        HistoryPoint(time, value)
    }.sortedBy { it.time }
    fun gaps(value: JsonElement?): List<Pair<Long, Long>> = (value as? JsonArray).orEmpty().mapNotNull { row ->
        val from = when (row) { is JsonArray -> row.getOrNull(0); is JsonObject -> row["from"]; else -> null }
        val to = when (row) { is JsonArray -> row.getOrNull(1); is JsonObject -> row["to"]; else -> null }
        val start = (from as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val end = (to as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        (start to end).takeIf { start < end }
    }
    val source = data["series"] as? JsonObject ?: JsonObject(emptyMap())
    val lines = mutableListOf(HistorySeries("load1", "Нагрузка · Load 1", points(source["load1"]), percent = false),
        HistorySeries("ram_pct", "Память", points(source["ram_pct"])))
    (data["disks"] as? JsonArray).orEmpty().forEach { item ->
        val disk = item as? JsonObject ?: return@forEach
        val mount = disk.text("mount") ?: "/"
        lines.add(HistorySeries("disk:$mount", "Диск $mount", points(disk["points"]), disk = true))
    }
    if (lines.none { it.disk }) lines.add(HistorySeries("disk_max_pct", "Диск · максимум",
        points(source["disk_max_pct"]), disk = true))
    val from = (data["from"] as? JsonPrimitive)?.longOrNull ?: fallback.from
    val to = (data["to"] as? JsonPrimitive)?.longOrNull ?: fallback.to
    val raw = (data["raw_values"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        key.toLongOrNull()?.let { stamp -> (value as? JsonObject)?.let { stamp to it } }
    }.toMap()
    val labels = ((data["labels"] as? JsonObject)?.get("disk_max_pct") as? JsonArray).orEmpty().mapNotNull { item ->
        val pair = item as? JsonArray ?: return@mapNotNull null
        (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull?.let { stamp ->
            (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.let { stamp to it }
        }
    }.toMap()
    return MetricHistory(if (from < to) HistoryWindow(from, to) else fallback, lines,
        gaps(data["gaps"]), gaps(data["disk_gaps"]), raw, labels, data.text("step") == "hour")
}
