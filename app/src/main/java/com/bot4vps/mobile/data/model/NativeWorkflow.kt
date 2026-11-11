package com.bot4vps.mobile.data.model

import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/** One navigation handoff, held in memory only. */
@Singleton
class NativeWorkflow @Inject constructor() {
    private var destination: String? = null
    private var values = JsonObject(emptyMap())
    @Synchronized fun put(target: String, value: JsonObject) { destination = target; values = value }
    @Synchronized fun take(target: String?): JsonObject? {
        if (target != destination) return null
        val result = values; destination = null; values = JsonObject(emptyMap()); return result
    }
}
