package com.bot4vps.mobile.data.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject

/** Acknowledge the loaded snapshot; events arriving afterwards remain unread. */
class EventReadController(private val mark: suspend (String) -> Unit) {
    private val confirmed = mutableSetOf<String>()

    suspend fun acknowledge(events: List<JsonObject>): Set<String> {
        val ids = events.mapNotNull { it.text("id") }.toSet()
        confirmed.retainAll(ids)
        confirmed += events.filter { it.flag("read") }.mapNotNull { it.text("id") }
        val pending = ids - confirmed
        val permits = Semaphore(4)
        val successful = coroutineScope {
            pending.map { id -> async {
                permits.withPermit {
                    try { mark(id); id }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
            } }.awaitAll().filterNotNull()
        }
        confirmed += successful
        return confirmed.toSet()
    }
}
