package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import java.util.UUID

data class BackupArchive(val id: String, val imported: Boolean, val row: JsonObject, val serverId: String?) {
    val key get() = (if (imported) "import:" else "backup:") + id
    val name get() = row.text("filename") ?: row.text("label") ?: id
    val base get() = "/api/backups/" + (if (imported) "imported/" else "") + com.bot4vps.mobile.data.api.encodeSegment(id)
    val query get() = serverId?.let { mapOf("server_id" to listOf(it)) } ?: emptyMap()
    companion object {
        fun list(data: JsonObject, serverId: String?): List<BackupArchive> =
            listOf("catalog" to false, "imported_archives" to true).flatMap { (field, imported) ->
                data.objects(field).mapNotNull { row ->
                    val target = row[if (imported) "destination" else "source"] as? JsonObject
                    val owner = target?.text("server_id")
                    val kind = if (imported) target?.text("scope") else row.text("type")
                    val matches = if (serverId == null) kind == "bot4vps" else kind == "server" && owner == serverId
                    val id = row.text(if (imported) "entry_key" else "backup_id")
                    if (!matches || id.isNullOrBlank()) null else BackupArchive(id, imported, row, owner)
                }
            }
    }
}

fun backupOperationId(row: JsonObject) = row.text("operation_id") ?: row.text("id")
fun backupOperationActive(row: JsonObject) = row.text("status") in setOf("queued", "running", "verifying")
fun backupPrepared(row: JsonObject): Boolean {
    val restore=row["restore"] as? JsonObject ?: return false
    val selection=restore["selection"] as? JsonObject ?: return false
    return row.text("type")=="restore" && row.text("status")=="completed" && row.text("stage")=="prepared" &&
        !backupOperationId(row).isNullOrBlank() && !restore.flag("mutation_started") && restore["plan"] is JsonObject &&
        selection.number("version")==3.0 && selection["source"] is JsonObject
}
fun backupError(row: JsonObject): String? = (row["error"] as? JsonObject)?.text("message") ?: row.text("error")

/** Changes only controls visible in the editor; retains source exclusions and limits. */
fun backupSettingsDraft(original: JsonObject?, server: Boolean, paths: List<String>, automatic: Boolean,
    dailyTime: String, keepLast: Int, encrypt: Boolean): JsonObject {
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(dailyTime)) { "Время: ЧЧ:ММ" }
    require(keepLast in 1..10000) { "Хранить от 1 до 10000 копий" }
    val clean = paths.map(String::trim).filter(String::isNotEmpty).distinct()
    require(!server || !automatic || clean.isNotEmpty()) { "Сначала выберите файлы и папки" }
    require(clean.all { it.startsWith('/') && '\u0000' !in it && '\\' !in it && it.split('/').none { p -> p == ".." || p == "." } }) { "Укажите абсолютные пути без .." }
    val old = original ?: JsonObject(emptyMap())
    return buildJsonObject {
        old.forEach { (k,v) -> put(k,v) }
        if (server) {
            put("schema_version",1)
            val sources = old.objects("sources").associateBy { it.text("path") }
            put("sources",JsonArray(clean.map { path -> sources[path] ?: buildJsonObject { put("path",path); put("exclusions",JsonArray(emptyList())) } }))
        }
        put("automatic", buildJsonObject {
            (old["automatic"] as? JsonObject).orEmpty().filterKeys { it != "timezone" }.forEach { (k,v) -> put(k,v) }
            put("enabled",automatic); put("daily_time",dailyTime); put("keep_last",keepLast)
        })
        if (old["limits"] == null) put("limits",JsonObject(emptyMap()))
        put("encrypt",encrypt)
    }
}

data class BackupRestoreOptions(val mode: String = "merge", val protective: Boolean = true,
    val selected: Set<String> = emptySet(), val partial: Boolean = false, val root: String = "", val password: String = "") {
    fun prepare(requiresRoot: Boolean): JsonObject = buildJsonObject {
        require(mode in setOf("merge","clean")) { "Неизвестный режим восстановления" }
        require(!partial || selected.isNotEmpty()) { "Выберите файлы или папки" }
        require(password.length<=256) { "Пароль архива: не больше 256 символов" }
        require(selected.size<=512 && selected.all { it.startsWith('/') && it.length<=4096 && '\u0000' !in it && '\\' !in it && it.split('/').none { p -> p == ".." || p == "." } }) { "Выберите допустимые пути из состава архива" }
        require(!requiresRoot || root.trim().startsWith('/') && root.trim() != "/" && root.length<=4096 && '\u0000' !in root && '\\' !in root && root.split('/').none { it == ".." || it == "." }) { "Укажите каталог восстановления, отличный от /" }
        put("restore_mode",mode); put("protective_backup",protective)
        put("selection_mode",if(partial) "selected" else "full")
        put("selected_paths",JsonArray(if(partial) selected.sorted().map(::JsonPrimitive) else emptyList()))
        if(requiresRoot) put("target_root",root.trim())
        if(password.isNotEmpty()) put("password",password)
        put("apply",false); put("request_id",UUID.randomUUID().toString())
    }
}
