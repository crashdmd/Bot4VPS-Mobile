package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class SecurityNotificationDeliveryTest {
    private val origin = "https://one.example"
    private val request = SecurityRequest("same-id", purpose = "login", nonce = "nonce")
    private val key = securityPromptKey(origin, request.id)
    private fun loaded(url: String = origin) = SecuritySnapshot(origin = url, supported = true, requests = listOf(request))

    @Test fun coldStartWithKnownProfilesKeepsSeenUntilAResponseOrProfileRemoval() {
        val profile = PanelProfile(origin, "Panel", "device")
        val initial = securityNotificationSnapshots(emptyMap(), listOf(profile))
        assertEquals(setOf(key), securityNotificationCheckpoint(setOf(key), initial, emptySet()))
        assertFalse(dismissSecurityNotification(key, initial))
        for (profiles in listOf(emptyList(), listOf(profile.copy(revoked = true)), listOf(profile.copy(localDisconnected = true)))) {
            val removed = securityNotificationSnapshots(mapOf(origin to loaded()), profiles)
            assertTrue(dismissSecurityNotification(key, removed))
            assertTrue(securityNotificationCheckpoint(setOf(key), removed, emptySet()).isEmpty())
        }
    }

    @Test fun serviceRestartOrNetworkErrorDoesNotClearDeliveredRequests() {
        val initial = mapOf(origin to SecuritySnapshot(origin = origin))
        val failed = mapOf(origin to loaded().copy(error = "No connection"))
        for (state in listOf(initial, failed)) {
            assertEquals(setOf(key), securityNotificationCheckpoint(setOf(key), state, emptySet()))
            assertFalse(dismissSecurityNotification(key, state))
            assertTrue(pendingSecurityNotifications(setOf(key), state).isEmpty())
        }
        assertTrue(pendingSecurityNotifications(setOf(key), mapOf(origin to loaded())).isEmpty())
    }

    @Test fun disabledAndroidNotificationsDoNotMarkUnpostedRequestsAsDelivered() {
        val state = mapOf(origin to loaded())
        assertTrue(securityNotificationCheckpoint(emptySet(), state, emptySet()).isEmpty())
        assertEquals(listOf(origin to request), pendingSecurityNotifications(emptySet(), state))
        assertEquals(setOf(key), securityNotificationCheckpoint(emptySet(), state, setOf(key)))
        assertTrue(pendingSecurityNotifications(setOf(key), state).isEmpty())
    }

    @Test fun identicalIdsOnDifferentPanelsAreDeliveredIndependently() {
        val other = "https://two.example"
        val state = mapOf(origin to loaded(), other to loaded(other))
        assertEquals(listOf(other to request), pendingSecurityNotifications(setOf(key), state))
        val posted = setOf(securityPromptKey(other, request.id))
        assertEquals(setOf(key) + posted, securityNotificationCheckpoint(setOf(key), state, posted))
    }

    @Test fun confirmedCompletionAndRemovedOrMovedProfilesClearOnlyTheirOwnNotifications() {
        val other = "https://two.example"
        val otherKey = securityPromptKey(other, request.id)
        val completed = mapOf(origin to loaded().copy(requests = emptyList()), other to loaded(other))
        assertEquals(setOf(otherKey), securityNotificationCheckpoint(setOf(key, otherKey), completed, emptySet()))
        val moved = mapOf(other to loaded(other))
        assertEquals(setOf(otherKey), securityNotificationCheckpoint(setOf(key, otherKey), moved, emptySet()))
        assertTrue(dismissSecurityNotification(key, moved))
    }
}
