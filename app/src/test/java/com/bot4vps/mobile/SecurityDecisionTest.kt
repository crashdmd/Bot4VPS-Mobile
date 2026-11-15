package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecurityDecisionTest {
    @Test fun signaturePayloadMatchesServerProtocolWithoutTrailingNewline() {
        assertArrayEquals("bot4vps-auth-v1\nid\nnonce\ndevice\napprove".toByteArray(Charsets.US_ASCII), authDecisionMessage("id", "nonce", "device", "approve"))
        assertTrue(runCatching { authDecisionMessage("id\nother", "nonce", "device", "approve") }.isFailure)
        assertTrue(runCatching { authDecisionMessage("id", "nonce", "device", "unknown") }.isFailure)
    }
    @Test fun loginQrIsSeparateFromPairingAndMustUseHttps() {
        fun qr(url: String, action: String = "login") = "b4vps-auth:" + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"version\":1,\"action\":\"$action\",\"url\":\"$url\",\"server_pin\":\"sha256/${Base64.getEncoder().encodeToString(ByteArray(32))}\",\"id\":\"id\",\"nonce\":\"nonce\"}".toByteArray())
        assertEquals("https://127.0.0.1:8444", readLoginQr(qr("https://127.0.0.1:8444/")).origin)
        assertTrue(runCatching { readLoginQr(qr("http://127.0.0.1:8444")) }.isFailure)
        assertTrue(runCatching { readLoginQr(qr("https://127.0.0.1:8444", "pair")) }.isFailure)
        assertTrue(runCatching { readLoginQr("b4vps:fixture") }.isFailure)
    }
    @Test fun hourWaitAllowsEarlyPhoneDecisionBut72HourWaitDoesNot() {
        val waiting = SecurityRequest("id", purpose = "unlink", nonce = "nonce", method = "totp", state = "waiting", deadline = 200.0)
        assertEquals(listOf("approve", "cancel"), securityActions(waiting, 100.0))
        assertEquals(listOf("cancel"), securityActions(waiting.copy(method = "delayed"), 100.0))
        assertEquals(listOf("approve", "cancel"), securityActions(waiting.copy(method = "delayed", state = "ready"), 200.0))
        assertEquals(emptyList<String>(), securityActions(waiting.copy(state = "completed"), 200.0))
    }
    @Test fun expiredLoginAndUnknownPurposeCannotBeApproved() {
        assertEquals(emptyList<String>(), securityActions(SecurityRequest("id", purpose = "login", expires = 100.0), 100.0))
        assertEquals(emptyList<String>(), securityActions(SecurityRequest("id", purpose = "future"), 100.0))
        assertEquals(emptyList<String>(), securityActions(SecurityRequest("id", purpose = "login", event = "new_login"), 100.0))
    }
    @Test fun serverCanFurtherRestrictAvailableActions() {
        assertEquals(listOf("cancel"), securityActions(SecurityRequest("id", purpose = "unlink", method = "totp", state = "waiting", actions = listOf("cancel")), 100.0))
    }
    @Test fun primaryTransferAndOtherPhoneUnlinkAreActionableButWorkerNoticeIsOnlyInformation() {
        val transfer = SecurityRequest("transfer", purpose = "primary_transfer", nonce = "nonce", expires = 200.0)
        assertEquals(listOf("approve", "reject"), securityActions(transfer, 100.0))
        val unlink = SecurityRequest("unlink", purpose = "unlink_other", state = "phone", targetDeviceId = "other")
        assertEquals(listOf("approve", "reject"), securityActions(unlink, 100.0))
        val notice = SecurityRequest("notice", event = "unlink_requested", deadline = 200.0, actions = listOf("ack"))
        assertTrue(securityActions(notice, 100.0).isEmpty())
        assertEquals(listOf(notice), visibleSecurityRequests(listOf(notice), 100.0))
        assertTrue(visibleSecurityRequests(listOf(unlink.copy(state = "code")), 100.0).isEmpty())
    }

}
