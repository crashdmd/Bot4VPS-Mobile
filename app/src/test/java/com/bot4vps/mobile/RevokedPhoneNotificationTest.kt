package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.revokedPhoneNotification
import org.junit.Assert.*
import org.junit.Test

class RevokedPhoneNotificationTest {
    @Test fun revocationPreviewNamesTheServiceAndExplainsAuthenticatorAccess() {
        val preview = revokedPhoneNotification("https://panel.test:8444")
        assertEquals("Доступ телефона отозван", preview.first)
        assertTrue(preview.second.contains("https://panel.test:8444"))
        assertTrue(preview.second.contains("Authenticator остаётся доступен"))
        assertFalse(preview.second.contains("разблокируйте"))
    }
}
