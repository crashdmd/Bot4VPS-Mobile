package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import org.junit.Assert.*
import org.junit.Test

class AuthenticatorTest {
    @Test fun rfc6238Sha1VectorsWorkWithoutAnyPanelOrNetwork() {
        val account = AuthenticatorAccount(name = "RFC", secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8)
        listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471", 1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130").forEach { (time, expected) -> assertEquals(expected, totpCode(account, time)) }
    }
    @Test fun parsesExternalTotpQrAndPreservesParameters() {
        val account = readTotpQr("otpauth://totp/Example%3Auser%40mail.test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8&period=60&algorithm=SHA256")
        assertEquals("user@mail.test", account.name); assertEquals("Example", account.issuer)
        assertEquals(60, account.period); assertEquals(8, account.digits); assertEquals("SHA256", account.algorithm)
        assertNull(account.panelOrigin)
    }
    @Test fun rejectsHotpDuplicateAndInvalidParameters() {
        listOf("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=7", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0").forEach { assertTrue(runCatching { readTotpQr(it) }.isFailure) }
    }
    @Test fun fixedWidthCodesAndNextPeriodAreCorrect() {
        val account = AuthenticatorAccount(name = "RFC", secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertEquals("287082", totpCode(account, 59)); assertEquals(totpCode(account, 30), totpCode(account, 59))
        assertNotEquals(totpCode(account, 59), totpCode(account, 60))
    }
}
