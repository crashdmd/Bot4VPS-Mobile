package com.bot4vps.mobile.data.model

fun scriptParameterError(script: ScriptInfo, values: Map<String, String>): String? {
    for (param in script.params.filter { it.visible(values) }) {
        val value = values[param.name].orEmpty()
        if (value.isBlank()) continue
        if (param.type == "number" && value.toDoubleOrNull()?.isFinite() != true)
            return "${param.label ?: param.name}: введите число"
        if (param.type == "select" && param.options.none { it.value == value })
            return "${param.label ?: param.name}: выберите значение из списка"
    }
    return null
}

fun ScriptParam.visible(values: Map<String,String>): Boolean {
    val source=condition ?: return true
    val colon=source.indexOf(':')
    if(colon<0) return true
    return values[source.substring(0,colon).trim()].orEmpty().equals(source.substring(colon+1),ignoreCase=true)
}
fun scriptRunValues(script: ScriptInfo, values: Map<String,String>): Map<String,String> = script.params.filter { it.visible(values) }
    .mapNotNull { p -> values[p.name]?.takeIf { it.isNotEmpty() }?.let { p.name to it } }.toMap()

fun scriptRunSummary(script: ScriptInfo, values: Map<String, String>): List<Pair<String, String>> =
    script.params.filter { it.visible(values) }.map { param ->
        val value = values[param.name].orEmpty()
        (param.label ?: param.name) to when {
            value.isEmpty() -> "Не указано"
            param.type == "password" -> "••••••"
            param.type == "bool" -> if (value.equals("true", true)) "Да" else "Нет"
            param.type == "select" -> param.options.find { it.value == value }?.label ?: value
            else -> value
        }
    }
