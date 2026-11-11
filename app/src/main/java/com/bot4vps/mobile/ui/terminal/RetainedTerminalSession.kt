package com.bot4vps.mobile.ui.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface TerminalConnection {
    fun send(message: String): Boolean
    fun close()
}

interface TerminalListener {
    fun opened()
    fun received(text: String)
    fun closed(message: String)
}

data class TerminalStatus(val message: String, val canConnect: Boolean = false)

/** All calls and transport callbacks run on the owning scope's main dispatcher. */
class RetainedTerminalSession(
    private val scope: CoroutineScope,
    private val open: suspend (TerminalListener) -> TerminalConnection,
    private val now: () -> Long,
    private val retentionMillis: Long = 5 * 60 * 1000L,
) {
    val buffer = TerminalBuffer()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val _status = MutableStateFlow(TerminalStatus("Подключение…"))
    val status = _status.asStateFlow()
    private val owners = mutableSetOf<Any>()
    private var connection: TerminalConnection? = null
    private var connecting: Job? = null
    private var watchdog: Job? = null
    private var expiry: Job? = null
    private var deadline: Long? = null
    private var generation = 0L
    private var invalidated = false
    private var connected = false
    private var everOpened = false

    fun resume(owner: Any) {
        if (invalidated) return
        // elapsedRealtime includes sleep; a delayed coroutine must not extend retention.
        expireIfIdle()
        owners.add(owner)
        expiry?.cancel(); expiry = null; deadline = null
        connect()
        if (connected) sendSize()
    }

    fun pause(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty() || invalidated) return
        val until = now() + retentionMillis
        deadline = until
        expiry = scope.launch {
            delay(retentionMillis)
            if (owners.isEmpty() && deadline == until) expire()
        }
    }

    fun connect() {
        if (invalidated || owners.isEmpty() || connection != null || connecting?.isActive == true) return
        val epoch = ++generation
        buffer.reset(); changed()
        _status.value = TerminalStatus(if (everOpened) "Открываем новую сессию…" else "Подключение…")
        val listener = object : TerminalListener {
            override fun opened() {
                if (epoch != generation || invalidated || expireIfIdle()) return
                connected = true
                _status.value = TerminalStatus(if (everOpened) "Подключено · новая сессия" else "Подключено")
                everOpened = true
                watchdog?.cancel(); watchdog = null
                sendSize()
            }
            override fun received(text: String) {
                if (epoch != generation || invalidated || expireIfIdle()) return
                // A bounded terminal grid, rather than an unbounded background output queue.
                buffer.append(text); changed()
            }
            override fun closed(message: String) {
                if (epoch != generation || invalidated) return
                stopConnection()
                _status.value = TerminalStatus(message, canConnect = true)
            }
        }
        connecting = scope.launch {
            try {
                val result = open(listener)
                if (epoch != generation || invalidated) result.close()
                else {
                    connection = result
                    // A transport may report onOpen before returning the connection.
                    if (connected) sendSize()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.closed(com.bot4vps.mobile.data.api.errorText(e))
            }
        }
        watchdog = scope.launch {
            delay(10_000)
            if (epoch == generation && !connected) {
                listener.closed("Не удалось подключиться за 10 секунд. Проверьте сеть и доступ к серверу.")
            }
        }
    }

    fun send(data: String) {
        if (invalidated || owners.isEmpty() || !connected) return
        connection?.send(buildJsonObject { put("type", "input"); put("data", data) }.toString())
    }

    fun resize(columns: Int, rows: Int) {
        if (buffer.columns != columns || buffer.rows != rows) {
            buffer.resize(columns, rows); changed()
        }
        if (owners.isNotEmpty()) sendSize()
    }

    fun close(message: String = "Сессия завершена") {
        expiry?.cancel(); expiry = null; deadline = null
        stopConnection()
        buffer.reset(); changed()
        _status.value = TerminalStatus(message, canConnect = !invalidated)
    }

    fun invalidate() {
        invalidated = true
        owners.clear()
        close("Сессия закрыта: доступ к панели изменился")
    }

    private fun expire() = close("Сессия закрыта после 5 минут вне терминала")
    private fun expireIfIdle(): Boolean {
        if (owners.isEmpty() && deadline?.let { now() >= it } == true) {
            expire()
            return true
        }
        return false
    }
    private fun changed() { _revision.value++ }
    private fun sendSize() {
        if (!connected || invalidated) return
        connection?.send(buildJsonObject {
            put("type", "resize"); put("cols", buffer.columns); put("rows", buffer.rows)
        }.toString())
    }
    private fun stopConnection() {
        generation++
        connected = false
        connecting?.cancel(); connecting = null
        watchdog?.cancel(); watchdog = null
        connection?.close(); connection = null
    }
}
