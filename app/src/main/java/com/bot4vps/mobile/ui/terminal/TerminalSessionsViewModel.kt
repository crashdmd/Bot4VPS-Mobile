package com.bot4vps.mobile.ui.terminal

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bot4vps.mobile.data.api.*
import com.bot4vps.mobile.data.local.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.*
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-owned sessions survive a popped terminal screen, rotation and the PIN screen. */
@HiltViewModel
class TerminalSessionsViewModel @Inject constructor(
    private val panel: NativePanelClient,
    preferences: PreferencesManager,
) : ViewModel() {
    private val sessions = mutableMapOf<String, RetainedTerminalSession>()
    private var identity: Pair<String?, String?>? = null

    init {
        viewModelScope.launch {
            combine(preferences.baseUrl, preferences.username) { url, user -> url to user }
                .distinctUntilChanged().collect { current ->
                    if (identity != null && identity != current) closeAll()
                    identity = current
                }
        }
    }

    fun session(serverId: String): RetainedTerminalSession = sessions.getOrPut(serverId) {
        RetainedTerminalSession(viewModelScope, { listener -> open(serverId, listener) }, SystemClock::elapsedRealtime)
    }

    fun closeAll() {
        sessions.values.forEach { it.invalidate() }
        sessions.clear()
    }

    private suspend fun open(serverId: String, listener: TerminalListener): TerminalConnection {
        val request = Request.Builder().url(panel.url("/api/servers/${encodeSegment(serverId)}/shell/ws")).build()
        val client = panel.httpClient(request.url)
        var socket: WebSocket? = null
        val proof = MobileWebSocketSession(viewModelScope,
            token = { panel.renewSocketSession(request.url) },
            send = { text -> socket?.send(text) == true },
            expired = { socket?.close(4401, "mobile_session_expired") })
        val stopped = AtomicBoolean(false)
        val events = Channel<() -> Unit>(64)
        val delivery = viewModelScope.launch { for (event in events) { event(); yield() } }
        fun deliver(event: () -> Unit) {
            // Apply backpressure on OkHttp's reader, never on Android's main thread.
            if (!stopped.get()) runBlocking { runCatching { events.send(event) } }
        }
        val connection = object : TerminalConnection {
            override fun send(message: String): Boolean = socket?.send(message) == true
            override fun close() {
                stopped.set(true); events.close(); delivery.cancel(); proof.stop()
                socket?.close(1000, "Клиент закрыл терминал"); socket?.cancel()
            }
        }
        try {
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    deliver {
                        listener.opened()
                        if (response.header("X-Bot4VPS-Mobile-Session-Renewal") == "1") {
                            response.header("X-Bot4VPS-Mobile-Session-TTL")?.toLongOrNull()?.let(proof::start)
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    deliver { if (!proof.receive(text)) listener.received(text) }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    proof.stop(); webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    proof.stop()
                    deliver {
                        listener.closed(if (code == 4401) "Доступ не подтверждён. При отзыве телефона нужна новая привязка." else "Сессия закрыта сервером")
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    proof.stop()
                    deliver { listener.closed(errorText(t)) }
                }
            })
        } catch (error: Exception) {
            connection.close()
            throw error
        }
        return connection
    }

    override fun onCleared() { closeAll() }
}
