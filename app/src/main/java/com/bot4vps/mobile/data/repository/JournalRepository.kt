package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class JournalRepository private constructor(private val client: (HttpUrl) -> OkHttpClient, private val url: suspend () -> HttpUrl) {
    @Inject constructor(panel: NativePanelClient) : this(panel::httpClient, { panel.url("/api/system/journal") })
    internal constructor(client: OkHttpClient, url: HttpUrl) : this({ client }, { url })
    fun frames() = callbackFlow<JournalFrame> {
        val target = url()
        val call = client(target).newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(
            Request.Builder().url(target).header("Accept", "text/event-stream").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if(!it.isSuccessful) throw ApiError(it.code, when(it.code) {
                            401,403 -> "Войдите заново, чтобы читать журнал службы"
                            404 -> "Журнал службы недоступен в этой версии панели"
                            else -> "Не удалось открыть журнал службы (${it.code})"
                        })
                        val input = it.body?.source() ?: throw IOException("Пустой поток журнала")
                        val decoder = JournalDecoder()
                        while(!call.isCanceled()) {
                            val line = input.readUtf8Line() ?: throw IOException("Соединение с журналом прервалось")
                            val frame = decoder.line(line) ?: continue
                            if(trySendBlocking(frame).isFailure) break
                            if(frame.event in setOf("end", "fail")) { close(); return }
                        }
                    }
                    close()
                } catch(e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }
}
