package com.bot4vps.mobile.data.repository

import kotlinx.coroutines.CancellationException

/** Delivery never approves, consumes or extends an authenticated request. */
internal suspend fun acknowledgePrimaryRequests(snapshot: SecuritySnapshot,
    send: suspend (String, String) -> Unit) {
    if (!snapshot.receiptSupported) return
    snapshot.requests.filter { request ->
        request.purpose == "primary_transfer" && request.id !in snapshot.receivedRequestIds
            && request.id.isNotBlank() && request.nonce.isNotBlank()
            && (request.expires == null || request.expires > snapshot.now())
    }.forEach { request ->
        try { send(request.id, request.nonce) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The panel keeps the request; retry on the next poll. */ }
    }
}
