package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DisabledPanelTotpTest {
    @Test fun cleanupPreservesOtherServicesExternalCodesAndNewGeneration() {
        val old = AuthenticatorAccount(name = "admin", secret = "JBSWY3DPEHPK3PXP", panelOrigin = "https://one.test", generation = "old")
        val newer = old.copy(id = "new", generation = "new")
        val other = old.copy(id = "other", panelOrigin = "https://two.test")
        val external = old.copy(id = "external", panelOrigin = null)
        val pending = old.copy(id = "pending", generation = null, active = false, setupId = "setup")
        val result = removeDisabledPanelTotp(listOf(old, newer, other, external, pending), "https://one.test", setOf("old"))
        assertEquals(listOf(newer, other, external, pending), result)
        assertEquals(result, removeDisabledPanelTotp(result, "https://one.test", setOf("old")))
    }
    @Test fun enableAndDisableRequestsAreActionableUntilExpiry() {
        for (purpose in listOf("two_factor_enable", "two_factor_disable")) {
            val request = SecurityRequest("id", purpose = purpose, nonce = "nonce", expires = 200.0)
            assertEquals(listOf(request), visibleSecurityRequests(listOf(request), 100.0))
            assertTrue(visibleSecurityRequests(listOf(request), 200.0).isEmpty())
        }
    }
}
