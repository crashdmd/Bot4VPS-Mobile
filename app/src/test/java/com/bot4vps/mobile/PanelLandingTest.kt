package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.openAuthenticatorFirst
import org.junit.Assert.*
import org.junit.Test

class PanelLandingTest {
    @Test fun firstInstallationStartsWithLocalAuthenticator() { assertTrue(openAuthenticatorFirst(false, false, false)) }
    @Test fun revokedOrDisconnectedPanelDoesNotTrapLocalAccountsBehindLogin() {
        assertTrue(openAuthenticatorFirst(true, true, true)); assertTrue(openAuthenticatorFirst(true, false, false))
    }
    @Test fun existingTrustMayConnectWithoutNewPairing() { assertFalse(openAuthenticatorFirst(true, true, false)) }
}
