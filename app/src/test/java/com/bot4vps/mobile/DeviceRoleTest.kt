package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.repository.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceRoleTest {
    private val origin = "https://panel.test:8444"
    private val chief = SecuritySnapshot(origin = origin, supported = true, isPrimary = true, primaryDeviceId = "chief")
    @Test fun missingManagementRouteDoesNotEraseTheConfirmedPrimaryRole(): Unit = runBlocking {
        val result = loadDeviceRole(origin, { chief }, { throw ApiError(404, "Not Found") })
        assertEquals(chief, result.snapshot)
        assertFalse(result.managementAvailable)
        assertTrue(result.phones.isEmpty())
        assertEquals("Управление телефонами недоступно на этом сервисе", result.error)
    }
    @Test fun ordinaryRoleDoesNotCallPrimaryManagement(): Unit = runBlocking {
        var calls = 0
        val result = loadDeviceRole(origin, { chief.copy(isPrimary = false) }, { calls++; emptyList() })
        assertFalse(result.snapshot.isPrimary); assertEquals(0, calls)
    }
    @Test fun cancellationIsNotPresentedAsAnApiFailure(): Unit = runBlocking {
        try { loadDeviceRole(origin, { chief }, { throw CancellationException() }); fail("Cancellation was swallowed") }
        catch (_: CancellationException) {}
    }
    @Test fun promotionAndDemotionComeFromTheCurrentPanelWithoutKeepingOldAuthority(): Unit = runBlocking {
        var role = chief.copy(isPrimary = false)
        var managementCalls = 0
        suspend fun load() = loadDeviceRole(origin, { role }, { managementCalls++; emptyList() })
        assertFalse(load().snapshot.isPrimary); assertEquals(0, managementCalls)
        role = chief
        assertTrue(load().managementAvailable); assertEquals(1, managementCalls)
        role = chief.copy(isPrimary = false)
        val demoted = load()
        assertFalse(demoted.snapshot.isPrimary); assertFalse(demoted.managementAvailable); assertTrue(demoted.phones.isEmpty())
        assertEquals(1, managementCalls)
    }
}
