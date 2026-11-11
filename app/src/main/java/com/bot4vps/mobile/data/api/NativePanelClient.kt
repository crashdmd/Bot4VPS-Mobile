package com.bot4vps.mobile.data.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bot4vps.mobile.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response)
            else response.close()
        }
    })
}

data class Upload(val uri: Uri, val filename: String, val mimeType: String, val size: Long?)

@Singleton
class NativePanelClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    val client: OkHttpClient,
    val json: Json,
    private val mobile: MobileAccessClient,
    val sensitiveKeys: com.bot4vps.mobile.data.local.SensitiveKeyStorage,
    val masterKeyClipboard: com.bot4vps.mobile.data.local.MasterKeyClipboard,
) {
    fun httpClient(url: HttpUrl): OkHttpClient = mobile.clientFor(client, url)
    suspend fun renewSocketSession(url: HttpUrl): String = withContext(Dispatchers.IO) {
        mobile.session(url, renewBeforeMillis = 90000)
    }
    suspend fun signMasterKeyEnrollment(challenge: JsonObject, publicKey: String): String {
        val url = url("/api/masterkey/phone/enroll/confirm")
        return withContext(Dispatchers.IO) { mobile.signMasterKeyEnrollment(url, challenge, publicKey) }
    }
    suspend fun masterKeyOperationPublicKey(): String {
        val url = url("/api/masterkey/phone/status")
        return withContext(Dispatchers.IO) { mobile.operationPublicKey(url) }
    }
    suspend fun signMasterKeyOperation(challenge: JsonObject, enrollmentPublicKey: String? = null): String {
        val url = url("/api/masterkey/phone/confirm")
        return withContext(Dispatchers.IO) { mobile.signMasterKeyOperation(url, challenge, enrollmentPublicKey) }
    }
    suspend fun url(path: String, query: Map<String, List<String>> = emptyMap()): HttpUrl =
        PanelUrl.endpoint(prefs.baseUrl.first() ?: error("Сначала укажите адрес панели"), path, query)

    fun upload(uri: Uri): Upload {
        var name = "upload.bin"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = it.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = it.getString(ni) ?: name
                if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
            }
        }
        return Upload(uri, name.substringAfterLast('/').substringAfterLast('\\'), context.contentResolver.getType(uri) ?: "application/octet-stream", size)
    }

    private fun requestBody(upload: Upload): RequestBody = object : RequestBody() {
        override fun contentType() = upload.mimeType.toMediaType()
        override fun contentLength() = upload.size ?: -1L
        override fun writeTo(sink: BufferedSink) {
            val stream = context.contentResolver.openInputStream(upload.uri) ?: error("Не удалось прочитать файл")
            stream.use {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    suspend fun request(
        method: String, path: String, query: Map<String, List<String>> = emptyMap(),
        body: JsonElement? = null, form: Map<String, String> = emptyMap(), uploads: Map<String, Upload> = emptyMap(), baseUrl: String? = null
    ): JsonElement {
        val req = buildRequest(method, path, query, body, form, uploads).let { if(baseUrl==null) it else it.newBuilder().url(PanelUrl.endpoint(baseUrl,path,query)).build() }
        return withContext(Dispatchers.IO) {
            httpClient(req.url).newCall(req).awaitResponse().use { response ->
                requireSuccess(response)
                val mime = response.body?.contentType()?.toString().orEmpty()
                if (mime.startsWith("image/")) {
                    val bytes = response.body?.bytes() ?: error("Пустой QR-код")
                    return@withContext buildJsonObject { put("qr", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)) }
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) JsonObject(mapOf("ok" to JsonPrimitive(true)))
                else runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
            }
        }
    }

    private suspend fun buildRequest(method: String, path: String, query: Map<String, List<String>>, body: JsonElement?, form: Map<String, String>, uploads: Map<String, Upload>): Request {
        val payload = when {
            form.isNotEmpty() || uploads.isNotEmpty() -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                form.forEach { (key, value) -> addFormDataPart(key, value) }
                uploads.forEach { (key, upload) -> addFormDataPart(key, upload.filename, requestBody(upload)) }
            }.build()
            method == "GET" -> null
            else -> (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        }
        return Request.Builder().url(url(path, query)).method(method, payload).build()
    }

    suspend fun download(method: String, path: String, query: Map<String, List<String>>, body: JsonElement?, destination: Uri) {
        val req = buildRequest(method, path, query, body, emptyMap(), emptyMap())
        withContext(Dispatchers.IO) {
            httpClient(req.url).newCall(req).awaitResponse().use { response ->
                requireSuccess(response)
                val source = response.body?.byteStream() ?: error("Пустой ответ панели")
                val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("Не удалось открыть файл для сохранения")
                output.use { target ->
                    source.use {
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val detail = runCatching {
            val value = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["detail"]
            when (value) {
                is JsonPrimitive -> value.content
                is JsonArray -> value.joinToString("\n") { item -> (item as? JsonObject)?.get("msg")?.jsonPrimitive?.content.orEmpty() }
                else -> null
            }
        }.getOrNull()
        throw ApiError(response.code, detail ?: when (response.code) {
            401 -> "Сессия истекла. Войдите заново."
            429 -> "Слишком много попыток. Повторите позднее."
            else -> "Ошибка панели (${response.code})"
        })
    }

}

fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
