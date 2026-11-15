package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PanelRemovalTest {
    private val a = PanelProfile("https://a", "A", "a1")
    private val b = PanelProfile("https://b", "B", "b1")
    @Test fun removingCurrentPanelSelectsAnotherConnectedPanel() {
        val result = removePanelConnection(listOf(a, b), a.url, a.deviceId, a.url)
        assertEquals(listOf(b), result.profiles); assertEquals(b.url, result.nextOrigin); assertTrue(result.switched)
    }
    @Test fun lastPanelRemovalLeavesNoSavedConnection() {
        val result = removePanelConnection(listOf(a), a.url, a.deviceId, a.url)
        assertTrue(result.profiles.isEmpty()); assertNull(result.nextOrigin); assertTrue(result.switched)
    }
    @Test fun revokingBackgroundPanelKeepsCurrentPanel() {
        val result = removePanelConnection(listOf(a, b), b.url, b.deviceId, a.url)
        assertEquals(a.url, result.nextOrigin); assertFalse(result.switched)
    }
    @Test fun lateOldDeviceRevocationCannotRemoveNewPairingAtSameOrigin() {
        val fresh = a.copy(deviceId = "new")
        val result = removePanelConnection(listOf(fresh, b), a.url, a.deviceId, a.url)
        assertNull(result.removed); assertEquals(listOf(fresh, b), result.profiles); assertEquals(a.url, result.nextOrigin)
    }
    @Test fun unavailableSavedConnectionsAreNotFallbackTargets() {
        val result = removePanelConnection(listOf(a, b.copy(revoked = true)), a.url, a.deviceId, a.url)
        assertNull(result.nextOrigin)
    }
    @Test fun severalRemainingPanelsRequireChoiceRatherThanSelectingFirst() {
        val c = PanelProfile("https://c", "C", "c1")
        val result = removePanelConnection(listOf(a, b, c), a.url, a.deviceId, a.url)
        assertTrue(result.choicesRequired); assertNull(result.nextOrigin)
        assertEquals(listOf(b, c), result.profiles)
    }
    @Test fun revokedOrDisconnectedEntriesDoNotForceChoice() {
        val c = PanelProfile("https://c", "C", "c1", localDisconnected = true)
        val result = removePanelConnection(listOf(a, b, c), a.url, a.deviceId, a.url)
        assertFalse(result.choicesRequired); assertEquals(b.url, result.nextOrigin)
    }
    @Test fun revocationOnImplicit443SwitchesAwayFromTheCurrentService() {
        val current = a.copy(url = "https://a:443")
        val result = removePanelConnection(listOf(current, b), current.url, current.deviceId, "https://a/")
        assertTrue(result.switched); assertEquals(b.url, result.nextOrigin); assertEquals(listOf(b), result.profiles)
    }

}
