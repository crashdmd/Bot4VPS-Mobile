package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.api.errorText
import com.bot4vps.mobile.data.model.PanelProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared set of independent workers for foreground dialogs and Android notifications. */
class PairedSecurityInbox(private val profiles: Flow<List<PanelProfile>>,
    private val afterDelivery: suspend (SecuritySnapshot) -> Unit = {},
    private val fetch: suspend (String) -> SecuritySnapshot) {
    private val snapshots = MutableStateFlow<Map<String, SecuritySnapshot>>(emptyMap())
    val state = snapshots.asStateFlow()
    private val pollLock = Mutex()
    suspend fun refresh(origin: String): SecuritySnapshot {
        val snapshot = fetch(origin)
        currentCoroutineContext().ensureActive()
        snapshots.update { if (origin in it) it + (origin to snapshot) else it }
        // Publish first. A lost receipt response cannot hide downloaded requests.
        try { withTimeoutOrNull(3000) { afterDelivery(snapshot) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Retry separately from fetching and user decisions. */ }
        return snapshot
    }
    suspend fun poll(interval: Long = 5000): Unit = pollLock.withLock {
        profiles.map { rows -> rows.filter { !it.revoked && !it.localDisconnected }.map { it.url }.distinct().sorted() }
            .distinctUntilChanged().collectLatest { origins ->
                snapshots.update { old -> origins.associateWith { old[it] ?: SecuritySnapshot(origin = it) } }
                coroutineScope { origins.forEach { origin -> launch {
                    var pause = interval
                    while (currentCoroutineContext().isActive) {
                        try { refresh(origin); pause = interval }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            snapshots.update { if (origin in it) it + (origin to it.getValue(origin).copy(error = errorText(e))) else it }
                            pause = (pause * 2).coerceAtMost(30_000)
                        }
                        delay(pause)
                    }
                } } }
            }
    }
}
