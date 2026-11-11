package com.bot4vps.mobile.data.api

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PanelEvent(val type: String, val data: JsonElement)

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealtimeHub @Inject constructor(private val panel: NativePanelClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source: Flow<PanelEvent> = callbackFlow {
        val request = Request.Builder().url(panel.url("/api/stream")).header("Accept","text/event-stream").build()
        val call = panel.httpClient(request.url).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) { close(ApiError(it.code,"Поток событий недоступен")); return }
                        val input = it.body?.source() ?: run { close(); return }
                        var type = "message"
                        val lines = mutableListOf<String>()
                        while (!isClosedForSend) {
                            val line = input.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (lines.isNotEmpty()) runCatching { panel.json.parseToJsonElement(lines.joinToString("\n")) }.getOrNull()?.let { value -> trySend(PanelEvent(type,value)) }
                                    lines.clear(); type = "message"
                                }
                                line.startsWith("event:") -> type = line.substringAfter(':').trim()
                                line.startsWith("data:") -> lines.add(line.substringAfter(':').removePrefix(" "))
                            }
                        }
                    }
                    close()
                } catch (e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }
    val reconnecting: Flow<PanelEvent> = source.onCompletion { cause -> if (cause == null) throw IOException("Поток событий закрыт") }.retryWhen { cause, attempt ->
        if (cause is ApiError && cause.status == 401) false
        else { delay((1000L * (attempt + 1)).coerceAtMost(15000)); true }
    }
    val events: SharedFlow<PanelEvent> = reconnecting.catch { /* Individual screens retain explicit HTTP errors and a refresh button. */ }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)
}
