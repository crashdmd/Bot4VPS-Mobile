package com.bot4vps.mobile.data.repository

import com.bot4vps.mobile.data.model.SecurityRequest
import com.bot4vps.mobile.data.model.PanelProfile
import com.bot4vps.mobile.data.model.securityPromptKey
import com.bot4vps.mobile.data.model.visibleSecurityRequests

/** Profiles establish which panels still exist, even before the first inbox response. */
internal fun securityNotificationSnapshots(inboxes: Map<String, SecuritySnapshot>, profiles: List<PanelProfile>): Map<String, SecuritySnapshot> =
    profiles.filter { !it.revoked && !it.localDisconnected }.associate { profile ->
        profile.url to (inboxes[profile.url] ?: SecuritySnapshot(origin = profile.url))
    }

internal fun pendingSecurityNotifications(seen: Set<String>, snapshots: Map<String, SecuritySnapshot>): List<Pair<String, SecurityRequest>> =
    snapshots.values.filter { it.supported == true && it.error == null }.flatMap { snapshot ->
        visibleSecurityRequests(snapshot.requests, snapshot.now()).filter {
            securityPromptKey(snapshot.origin, it.id) !in seen
        }.map { snapshot.origin to it }
    }

internal fun dismissSecurityNotification(key: String, snapshots: Map<String, SecuritySnapshot>): Boolean {
    val snapshot = snapshots[key.substringBefore('\n')] ?: return true
    if (snapshot.supported == null || snapshot.error != null) return false
    return visibleSecurityRequests(snapshot.requests, snapshot.now()).none {
        securityPromptKey(snapshot.origin, it.id) == key
    }
}

/** An initial/failed fetch is not evidence that a previously delivered request disappeared. */
internal fun securityNotificationCheckpoint(seen: Set<String>, snapshots: Map<String, SecuritySnapshot>, posted: Set<String>): Set<String> =
    seen.filterNot { dismissSecurityNotification(it, snapshots) }.toSet() +
        posted.filterNot { dismissSecurityNotification(it, snapshots) }
