package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MasterKeyViewTest {
    private fun response(vararg fields: Pair<String, String>) = buildJsonObject { put("ok", true); fields.forEach { (key, value) -> put(key, value) } }
    @Test fun successfulLocalAuthenticationWaitsForExplicitViewAndNeverRequestsProofTwice() = runBlocking {
        for (method in listOf("PIN", "biometric")) {
            var now = 0L; var authentications = 0
            val calls = mutableListOf<String>()
            val viewer = MasterKeyViewer({ path, _ -> calls += path
                if (path.endsWith("challenge")) response("challenge_id" to "once", "nonce" to "nonce", "device_id" to "device") else response("key" to "secret")
            }, { now })
            viewer.prepare(MasterKeyStep.LOCAL)
            val operation = launch {
                viewer.authenticateLocal(authorize = { action -> authentications++; action(ByteArray(32)) }, perform = {
                    viewer.local(signKeystore = { "keystore" }) { method + "-signature" }
                })
            }
            yield()
            assertEquals(MasterKeyStep.READY, viewer.state.value.step)
            assertNull(viewer.state.value.key); assertTrue(calls.isEmpty())
            now += 60_000; viewer.expire() // Background lock delay is not a proof TTL.
            assertEquals(MasterKeyStep.READY, viewer.state.value.step)
            viewer.approve(); viewer.approve(); operation.join()
            assertEquals(1, authentications)
            assertEquals(listOf("/api/masterkey/phone/challenge", "/api/masterkey/phone/confirm"), calls)
            assertEquals("secret", viewer.state.value.key)
            now += 29_999; viewer.expire(); assertEquals("secret", viewer.state.value.key)
            now++; viewer.expire(); assertNull(viewer.state.value.key)
        }
    }

    @Test fun cancelOrBackgroundAfterIdentityConfirmationNeverRequestsKeyAndClearsSeed() = runBlocking {
        for (background in listOf(false, true)) {
            val seed = ByteArray(32) { 1 }; var requests = 0
            val viewer = MasterKeyViewer({ _, _ -> requests++; response("key" to "unexpected") })
            viewer.prepare(MasterKeyStep.LOCAL)
            val operation = launch {
                viewer.authenticateLocal(authorize = { action -> try { action(seed) } finally { seed.fill(0) } }, perform = { viewer.local { "proof" } })
            }
            yield(); assertEquals(MasterKeyStep.READY, viewer.state.value.step)
            if (background) viewer.background() else viewer.close()
            operation.join(); viewer.foreground(); viewer.approve()
            assertTrue(seed.all { it == 0.toByte() }); assertEquals(0, requests); assertNull(viewer.state.value.key)
        }
    }

    @Test fun passwordStepReplacesItselfWithOtpWithoutKeepingCredentials() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val viewer = MasterKeyViewer({ path, body -> calls += path to body; response("channel" to "totp") })
        viewer.begin("panel-password")
        assertEquals(MasterKeyStep.CODE, viewer.state.value.step)
        assertEquals("totp", viewer.state.value.channel)
        assertNull(viewer.state.value.key)
        assertFalse(viewer.state.value.toString().contains("panel-password"))
        assertEquals("/api/masterkey/phone/view", calls.single().first)
    }
    @Test fun keyRequiresBothStepsAndReplacesCodeActions() = runBlocking {
        val calls = mutableListOf<String>()
        val viewer = MasterKeyViewer({ path, _ -> calls += path; if (path.endsWith("/confirm")) response("key" to "master-secret") else response("channel" to "totp") })
        viewer.confirm("123456"); assertTrue(calls.isEmpty())
        viewer.begin("password"); viewer.confirm("123456")
        assertEquals(MasterKeyStep.KEY, viewer.state.value.step)
        assertEquals("master-secret", viewer.state.value.key)
        assertNull(viewer.state.value.channel)
        viewer.close(); assertNull(viewer.state.value.key)
        assertEquals(MasterKeyStep.PASSWORD, viewer.state.value.step)
    }
    @Test fun wrongPasswordNeverAdvances() = runBlocking {
        val viewer = MasterKeyViewer({ _, _ -> error("Неверный пароль") })
        viewer.begin("wrong")
        assertEquals(MasterKeyStep.PASSWORD, viewer.state.value.step)
        assertEquals("Неверный пароль", viewer.state.value.error)
        assertNull(viewer.state.value.key)
    }
    @Test fun wrongCodeKeepsOnlyTheCodeStep() = runBlocking {
        val viewer = MasterKeyViewer({ path, _ -> if (path.endsWith("/confirm")) error("Неверный код") else response("channel" to "totp") })
        viewer.begin("password"); viewer.confirm("123456")
        assertEquals(MasterKeyStep.CODE, viewer.state.value.step)
        assertEquals("Неверный код", viewer.state.value.error)
        assertNull(viewer.state.value.key)
    }
    @Test fun resendIsOnlyForTelegramAfterCooldown() = runBlocking {
        var now = 100_000L; val calls = mutableListOf<String>()
        val viewer = MasterKeyViewer({ path, _ -> calls += path; response("channel" to "telegram") }, { now })
        viewer.begin("password"); viewer.resend(); assertEquals(1, calls.size)
        now += 120_000; viewer.resend(); assertEquals(2, calls.size)
        assertEquals("/api/masterkey/phone/view/resend", calls.last())
        assertEquals(now + 120_000, viewer.state.value.resendAt)
    }
    @Test fun totpHasNoResendAction() = runBlocking {
        val calls = mutableListOf<String>()
        val viewer = MasterKeyViewer({ path, _ -> calls += path; response("channel" to "totp") })
        viewer.begin("password"); viewer.resend(); assertEquals(1, calls.size)
    }
    @Test fun unexpectedChannelDoesNotSkipOwnerConfirmation() = runBlocking {
        val viewer = MasterKeyViewer({ _, _ -> response("channel" to "none", "key" to "unexpected-secret") })
        viewer.begin("password")
        assertEquals(MasterKeyStep.PASSWORD, viewer.state.value.step)
        assertNull(viewer.state.value.key)
    }
    @Test fun closingDialogDiscardsALateKeyResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<JsonObject>()
        val viewer = MasterKeyViewer({ path, _ -> if (path.endsWith("/confirm")) { entered.complete(Unit); finish.await() } else response("channel" to "totp") })
        viewer.begin("password")
        val job = launch { viewer.confirm("123456") }
        entered.await(); viewer.close(); finish.complete(response("key" to "late-secret")); job.join()
        assertEquals(MasterKeyStep.PASSWORD, viewer.state.value.step)
        assertNull(viewer.state.value.key)
    }
    @Test fun malformedCodeDoesNotReachTheServer() = runBlocking {
        val calls = mutableListOf<String>()
        val viewer = MasterKeyViewer({ path, _ -> calls += path; response("channel" to "totp") })
        viewer.begin("password"); viewer.confirm("12345"); viewer.confirm("12345x")
        assertEquals(1, calls.size)
    }
    @Test fun localProofNeverSendsAnInterfaceConfirmationFlagAndExpiresTheKey() = runBlocking {
        var now = 1000L; val calls = mutableListOf<Pair<String, JsonObject>>()
        val viewer = MasterKeyViewer({ path, body -> calls += path to body
            if (path.endsWith("challenge")) response("challenge_id" to "challenge", "nonce" to "nonce", "device_id" to "device") else response("key" to "secret")
        }, { now })
        viewer.prepare(MasterKeyStep.LOCAL); viewer.local { "crypto-signature" }
        assertEquals("crypto-signature", calls.last().second.text("signature"))
        assertFalse(calls.last().second.containsKey("pin_verified"))
        now += 29999; viewer.expire(); assertEquals("secret", viewer.state.value.key)
        now++; viewer.expire(); assertNull(viewer.state.value.key)
    }
    @Test fun closingDuringBiometricConfirmationNeverSendsTheProof() = runBlocking {
        val signed = CompletableDeferred<String>(); val started = CompletableDeferred<Unit>(); var proofs = 0
        val viewer = MasterKeyViewer({ path, _ -> if (path.endsWith("confirm")) { proofs++; response("key" to "late") } else response("challenge_id" to "challenge") })
        viewer.prepare(MasterKeyStep.LOCAL)
        val job = launch { viewer.local { started.complete(Unit); signed.await() } }
        started.await(); viewer.close(); signed.complete("signature"); job.join()
        assertEquals(0, proofs); assertNull(viewer.state.value.key)
    }

    @Test fun ownerFactorsAreOnlyRequiredWhenProtectionIsDisabled() {
        assertEquals(MasterKeyStep.SETUP_PIN, masterKeyInitialStep(true, false, false))
        assertEquals(MasterKeyStep.BLOCKED, masterKeyInitialStep(false, false, false))
        val message = masterKeyUnavailableMessage()
        assertTrue(message.contains("второго фактора")); assertTrue(message.contains("CLI")); assertTrue(message.contains("PIN"))
        val viewer = MasterKeyViewer({ _, _ -> error("Blocked view must not submit credentials") })
        viewer.blocked(message); assertEquals(MasterKeyStep.BLOCKED, viewer.state.value.step)
        assertEquals(message, viewer.state.value.error); assertNull(viewer.state.value.key)
        assertEquals(MasterKeyStep.LOCAL, masterKeyInitialStep(true, true, false))
        assertEquals(MasterKeyStep.SETUP_PIN, masterKeyInitialStep(true, false, true))
        assertEquals(MasterKeyStep.PASSWORD, masterKeyInitialStep(false, true, true))
        assertEquals(MasterKeyStep.BLOCKED, masterKeyInitialStep(false, true, false))
    }

    @Test fun firstProtectedViewUsesBothKeysWithoutAnOwnerPasswordStep() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val viewer = MasterKeyViewer({ path, body ->
            calls += path to body
            when {
                path.endsWith("enroll/challenge") -> response("public_key" to "guard", "device_operation_public_key" to "native", "challenge_id" to "enroll", "nonce" to "nonce", "device_id" to "device")
                path.endsWith("enroll/confirm") -> buildJsonObject { put("ok", true); put("enrolled", true) }
                path.endsWith("/challenge") -> response("challenge_id" to "view", "nonce" to "nonce", "device_id" to "device")
                else -> response("key" to "master-secret")
            }
        })
        viewer.prepare(MasterKeyStep.SETUP_PIN)
        viewer.enroll("guard", "native", { "routine-proof" }, { "pin-proof" }, { "keystore-proof" })
        assertEquals(MasterKeyStep.LOCAL, viewer.state.value.step)
        viewer.local(signKeystore = { "native-view-proof" }) { "pin-view-proof" }
        assertEquals(MasterKeyStep.KEY, viewer.state.value.step)
        assertTrue(calls.all { it.first.startsWith("/api/masterkey/phone/") && !it.second.containsKey("password") })
        assertEquals("keystore-proof", calls[1].second.text("keystore_signature"))
        assertEquals("native-view-proof", calls.last().second.text("keystore_signature"))
    }

    @Test fun codeStepSurvivesGoingToAuthenticatorOrTelegram() = runBlocking {
        for (channel in listOf("totp", "telegram")) {
            val viewer = MasterKeyViewer({ path, _ -> if (path.endsWith("confirm")) response("key" to "secret") else response("channel" to channel) })
            viewer.begin("password")
            val before = viewer.state.value
            assertFalse(viewer.background())
            assertEquals(MasterKeyStep.CODE, viewer.state.value.step)
            assertEquals(channel, viewer.state.value.channel)
            assertEquals(before.resendAt, viewer.state.value.resendAt)
            assertFalse(viewer.state.value.toString().contains("password"))
            viewer.foreground(); viewer.confirm("123456")
            assertEquals(MasterKeyStep.KEY, viewer.state.value.step)
            assertTrue(viewer.background()); assertNull(viewer.state.value.key)
        }
    }

    @Test fun delayedKeyResponseCannotRevealASecretInBackground() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<JsonObject>()
        val viewer = MasterKeyViewer({ path, _ -> if (path.endsWith("confirm")) { started.complete(Unit); finish.await() } else response("channel" to "totp") })
        viewer.begin("password")
        val job = launch { viewer.confirm("123456") }
        started.await(); viewer.background(); finish.complete(response("key" to "late-secret")); job.join()
        assertNull(viewer.state.value.key)
        assertEquals(MasterKeyStep.PASSWORD, viewer.state.value.step)
    }

    @Test fun delayedPasswordCheckCanStillLeaveTheCodeStepReadyInBackground() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<JsonObject>()
        val viewer = MasterKeyViewer({ _, _ -> started.complete(Unit); finish.await() })
        val job = launch { viewer.begin("password") }
        started.await(); viewer.background(); finish.complete(response("channel" to "totp")); job.join()
        assertEquals(MasterKeyStep.CODE, viewer.state.value.step)
        assertNull(viewer.state.value.key)
    }

}
