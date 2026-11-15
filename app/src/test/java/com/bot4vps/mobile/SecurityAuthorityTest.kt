package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class SecurityAuthorityTest {
    private val worker = SecuritySnapshot(supported = true, primaryDeviceId = "chief", twoFactorEnabled = true)
    @Test fun ordinaryPhoneCannotConfirmSensitiveRequestsEvenIfAnOldServerDeliversThem() {
        for (purpose in listOf("login", "unlink", "unlink_other", "two_factor_enable", "two_factor_disable", "protection_disable")) {
            val request = SecurityRequest("id", purpose = purpose)
            assertFalse(purpose, mayConfirmSecurity(worker, request))
            assertTrue(purpose, mayConfirmSecurity(worker.copy(isPrimary = true), request))
        }
    }
    @Test fun missingRoleSupportCannotGrantAuthority() {
        assertFalse(mayConfirmSecurity(worker.copy(supported = false, isPrimary = true), SecurityRequest("id", purpose = "two_factor_disable")))
    }
    @Test fun authorizedInitialSetupAndNewPrimaryAcceptanceRemainPossible() {
        assertFalse(mayConfirmSecurity(worker.copy(primaryDeviceId = null, deviceCount = 3), SecurityRequest("id", purpose = "two_factor_enable")))
        assertFalse(mayConfirmSecurity(worker.copy(primaryDeviceId = null, deviceCount = 1), SecurityRequest("id", purpose = "two_factor_enable")))
        assertTrue(mayConfirmSecurity(worker.copy(isPrimary = true), SecurityRequest("id", purpose = "two_factor_enable")))
        assertTrue(mayConfirmSecurity(worker, SecurityRequest("id", purpose = "primary_transfer")))
        assertTrue(mayConfirmSecurity(worker.copy(twoFactorEnabled = false), SecurityRequest("id", purpose = "login")))
    }
    @Test fun unlinkConfirmationExplainsAutomaticOrManualChoice() {
        assertTrue(primaryUnlinkWarning(true, 2).contains("автоматически станет основным"))
        assertTrue(primaryUnlinkWarning(true, 3).contains("только паролем администратора"))
        assertEquals("", primaryUnlinkWarning(false, 2))
    }
}
