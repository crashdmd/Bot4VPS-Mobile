package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*

/** Only a continuation issued by the server can be confirmed by the user. */
data class ServerFirewallDecision(val kind: String, val continuation: JsonObject, val rules: List<JsonObject>,
    val omitted: List<String>, val title: String, val explanation: String) {
    fun body(original: JsonObject, selected: Set<Int>): JsonObject = buildJsonObject {
        original.forEach { (key, value) -> if (key != "continuation" && key != "selected_rules") put(key, value) }
        put("continuation", continuation)
        if (kind == "skip_ambiguous_rules") put("selected_rules", JsonArray(rules.filterIndexed { index, _ -> index in selected }.map { rule ->
            buildJsonObject { put("port", (rule["port"] as? JsonPrimitive)?.intOrNull ?: 0); put("protocol", rule.text("protocol") ?: "tcp"); put("source", rule.text("source") ?: "") }
        }))
    }
    companion object {
        fun from(result: JsonObject): ServerFirewallDecision? {
            val data = result["data"] as? JsonObject ?: return null
            val kind = data.text("decision_required") ?: return null
            if (kind !in setOf("continue_without_rule_migration", "skip_ambiguous_rules", "skip_failed_rules")) return null
            if (kind == "skip_failed_rules" && (data["rollback"] as? JsonObject)?.flag("verified") != true) return null
            val token = data["continue_with"] as? JsonObject ?: return null
            if (token.isEmpty()) return null
            val rules = data.objects("migratable_rules")
            return when (kind) {
                "continue_without_rule_migration" -> ServerFirewallDecision(kind, token, emptyList(), emptyList(), "Правила не удалось прочитать", "Продолжить без переноса существующих правил? Отмена сохраняет текущий сетевой экран.")
                "skip_ambiguous_rules" -> ServerFirewallDecision(kind, token, rules,
                    data.objects("ambiguous_rules").map { it.text("rule") ?: "Правило без описания" }, "Выберите правила для переноса", "Неотмеченные правила не будут перенесены. Доступ по SSH сохраняется.")
                else -> ServerFirewallDecision(kind, token, emptyList(), data.objects("failed_rules").map(::firewallRuleLabel), "Часть правил не перенесена", "Предыдущие изменения откатаны, доступ по SSH проверен. Продолжить новую попытку без перечисленных правил?")
            }
        }
    }
}
fun firewallRuleLabel(rule: JsonObject): String = "${rule.text("port") ?: "—"}/${rule.text("protocol") ?: "tcp"}" +
    (rule.text("source")?.takeIf(String::isNotBlank)?.let { " · $it" } ?: "")
