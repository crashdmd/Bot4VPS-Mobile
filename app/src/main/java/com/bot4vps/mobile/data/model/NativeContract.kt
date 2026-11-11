package com.bot4vps.mobile.data.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class NativeOperation(
    val id: String, val method: String, val path: String, val section: String,
    val title: String, val group: String = "", val fields: List<JsonObject> = emptyList(),
    val body: JsonObject? = null, val destructive: Boolean = false, val download: Boolean = false
)

@Serializable
data class NativeContract(
    val version: String, val sections: Map<String, String>, val labels: Map<String, String>, val operations: List<NativeOperation>
)

@Singleton
class ContractRepository @Inject constructor(@ApplicationContext private val context: Context, private val json: Json) {
    private var cached: NativeContract? = null
    suspend fun load(): NativeContract = cached ?: withContext(Dispatchers.IO) {
        context.assets.open("native_contract.json").bufferedReader().use {
            json.decodeFromString<NativeContract>(it.readText()).also { contract -> cached = contract }
        }
    }
}

fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
fun JsonObject.properties(): Map<String, JsonObject> = (get("properties") as? JsonObject)?.mapValues { it.value.jsonObject } ?: emptyMap()
fun JsonObject.required(): Set<String> = (get("required") as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

object NativeForm {
    fun initial(schema: JsonObject): JsonElement? {
        schema["default"]?.let { return it }
        if (schema.flag("nullable")) return null
        return when (schema.text("type")) {
            "object" -> JsonObject(schema.properties().mapNotNull { (key, value) -> initial(value)?.let { key to it } }.toMap())
            "array" -> JsonArray(emptyList())
            else -> null
        }
    }

    fun validate(schema: JsonObject, value: JsonElement?, name: String = ""): String? {
        if (value == null) return null
        if (value == JsonNull) return if (schema.flag("nullable")) null else "Поле нельзя очистить: $name"
        val obj = value as? JsonObject
        if (schema.text("type") == "object") {
            if (obj == null) return "Проверьте параметры: $name"
            schema.properties().forEach { (key, field) ->
                val v = obj?.get(key)
                val label = field.text("title") ?: key
                if (key in schema.required() && (v == null || v == JsonNull && !field.flag("nullable") || key != "content" && v is JsonPrimitive && v.isString && v.content.isBlank())) return "Заполните: $label"
                if (v != null) validate(field, v, label)?.let { return it }
            }
        }
        if (schema.text("type") == "array") {
            val items = value as? JsonArray ?: return "Проверьте список: $name"
            val minimum = (schema["minItems"] as? JsonPrimitive)?.intOrNull
            if (minimum != null && items.size < minimum) return "Выберите минимум $minimum: $name"
            val field = schema["items"] as? JsonObject ?: return null
            items.forEach { validate(field, it, name)?.let { error -> return error } }
        }
        val primitive = value as? JsonPrimitive ?: return null
        when (schema.text("type")) {
            "string" -> if (!primitive.isString) return "Введите текст: $name"
            "integer" -> if (primitive.longOrNull == null) return "Введите целое число: $name"
            "number" -> if (primitive.doubleOrNull == null) return "Введите число: $name"
            "boolean" -> if (primitive.booleanOrNull == null) return "Выберите значение: $name"
        }
        val number = primitive.doubleOrNull
        val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
        val maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
        if (number != null && minimum != null && number < minimum) return "$name: минимум $minimum"
        if (number != null && maximum != null && number > maximum) return "$name: максимум $maximum"
        val minLength = (schema["minLength"] as? JsonPrimitive)?.intOrNull
        val maxLength = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
        if (primitive.isString && minLength != null && primitive.content.length < minLength) return "$name: минимум $minLength символов"
        if (primitive.isString && maxLength != null && primitive.content.length > maxLength) return "$name: максимум $maxLength символов"
        val choices = schema["enum"] as? JsonArray
        if (choices != null && value !in choices) return "Выберите допустимое значение: $name"
        return null
    }

    fun redact(schema: JsonObject, value: JsonElement?): JsonElement? {
        if (value == null || value == JsonNull) return value
        if (schema.flag("secret")) return JsonPrimitive("••••••")
        if (value is JsonObject) return JsonObject(value.mapValues { (key, v) -> redact(schema.properties()[key] ?: JsonObject(emptyMap()), v) ?: JsonNull })
        if (value is JsonArray) return JsonArray(value.map { redact(schema["items"] as? JsonObject ?: JsonObject(emptyMap()), it) ?: JsonNull })
        return value
    }
}
