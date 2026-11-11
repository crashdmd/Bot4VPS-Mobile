package com.bot4vps.mobile.data.api

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Refresh proof on the existing socket; no reconnect or new SSH process. */
class MobileWebSocketSession(
    private val scope: CoroutineScope,
    private val token: suspend () -> String,
    private val send: (String) -> Boolean,
    private val expired: () -> Unit,
) {
    private var job: Job? = null
    private var stopped = false

    @Synchronized
    fun start(seconds: Long) {
        if (stopped || seconds !in 1..900) return
        job?.cancel()
        job = scope.launch {
            try {
                withTimeout(seconds * 1000) {
                    delay(maxOf(1000, (seconds - 60) * 1000))
                    while (true) {
                        try {
                            val fresh = token()
                            val message = buildJsonObject {
                                put("type", "mobile_session"); put("session", fresh)
                            }.toString()
                            if (send(message)) awaitCancellation() // Server acknowledgement starts the next timer.
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A transient HTTP failure need not interrupt the SSH session.
                        }
                        delay(5000)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                expired()
            }
        }
    }

    fun receive(text: String): Boolean {
        if (text.length > 8192) return false
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        if ((value["type"] as? JsonPrimitive)?.contentOrNull != "mobile_session") return false
        if ((value["status"] as? JsonPrimitive)?.contentOrNull == "renewed") {
            (value["expires_in"] as? JsonPrimitive)?.longOrNull?.let(::start)
        }
        return true
    }

    @Synchronized
    fun stop() { stopped = true; job?.cancel(); job = null }
}
