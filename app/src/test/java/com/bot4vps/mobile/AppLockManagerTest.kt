package com.bot4vps.mobile

import com.bot4vps.mobile.data.local.*
import com.bot4vps.mobile.data.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockManagerTest {
    private val digest = LockPin(ByteArray(32) { it.toByte() }.lockHex(), "d7ebc688905c80a0da69967d6d3f7a93dc8978468ebfe75a66bfaf306c47a48f")
    private class MemoryStorage(var value: String?) : AppLockStorage {
        override fun read() = value
        override fun write(value: String?) { this.value = value }
        val record get() = value?.let(::decodeAppLockRecord)
    }
    private class Biometrics : AppLockBiometricOperations {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        var enrollments = 0
        var sealCalls = 0
        var authorized = false
        var enrollmentError: Exception? = null
        var onEnrolled: (() -> Unit)? = null
        val removed = mutableListOf<String>()
        override fun enrollment(): Pair<String, Cipher> {
            enrollments++
            enrollmentError?.let { throw it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            onEnrolled?.invoke()
            return "bot4vps-lock-${UUID.randomUUID()}" to cipher
        }
        override fun seal(alias: String, cipher: Cipher): LockBiometricSeal {
            check(authorized) { "Authentication must precede any payload operation" }
            sealCalls++
            return LockBiometricCipher.seal(alias, cipher)
        }
        override fun sealSeed(alias: String, cipher: Cipher, seed: ByteArray): LockBiometricSeal {
            check(authorized); sealCalls++
            return LockBiometricCipher.sealSeed(alias, cipher, seed)
        }
        override fun openSeed(seal: LockBiometricSeal, cipher: Cipher): ByteArray {
            check(authorized)
            return LockBiometricCipher.openSeed(seal, cipher)
        }
        override fun decrypt(seal: LockBiometricSeal): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, seal.iv.lockBytes()))
        }
        override fun verify(seal: LockBiometricSeal, cipher: Cipher): Boolean {
            check(authorized)
            return LockBiometricCipher.verify(seal, cipher)
        }
        override fun remove(alias: String?) { if (alias != null) removed += alias }
    }
    private class SeedProtection : AppLockSeedProtection {
        private val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
        var unavailable = false
        val removed = mutableListOf<String>()
        override fun protect(seed: SensitivePinSeed): SensitivePinSeed {
            check(!unavailable)
            if (seed.keystoreWrapped) return seed
            return SensitivePinDeviceCipher.protect(seed, Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) })
        }
        override fun unprotect(seed: SensitivePinSeed): SensitivePinSeed {
            check(!unavailable)
            if (!seed.keystoreWrapped) return seed
            return SensitivePinDeviceCipher.unprotect(seed, Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, seed.envelope.ciphertext.lockBytes().copyOfRange(0, 12)))
            })
        }
        override fun remove(id: String) { removed += id }
    }
    private data class Fixture(val manager: AppLockManager, val storage: MemoryStorage, val biometrics: Biometrics, val seedProtection: SeedProtection)
    private fun TestScope.fixture(record: AppLockRecord? = AppLockRecord(pin = digest)): Fixture {
        val storage = MemoryStorage(record?.let { Json.encodeToString(it) })
        val biometrics = Biometrics()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val protection = SeedProtection()
        val manager = AppLockManager(storage, biometrics, backgroundScope, dispatcher, dispatcher, protection, { testScheduler.currentTime })
        manager.foreground(); runCurrent()
        return Fixture(manager, storage, biometrics, protection)
    }
    private fun TestScope.unlock(fixture: Fixture) {
        fixture.manager.unlock("0123"); runCurrent()
        assertTrue(fixture.manager.state.value.allowsContent)
    }
    @Test fun samePinUnlocksEnablesBiometricsAndDisablesLock() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent()
        val pending = f.manager.prompt.value!!
        assertNull(f.manager.state.value.error)
        assertEquals(0, f.storage.record!!.failedAttempts)
        assertEquals(0, f.biometrics.sealCalls)
        assertFalse(f.manager.state.value.biometricEnabled)
        f.biometrics.authorized = true
        f.manager.biometricResult(pending.cipher); runCurrent()
        assertTrue(f.manager.state.value.biometricEnabled)
        assertTrue(f.storage.record!!.credential.verify("0123".toCharArray()))
        assertEquals(1, f.biometrics.sealCalls)
        f.manager.disable("0123"); runCurrent()
        assertNull(f.storage.value)
        assertEquals(LockPhase.DISABLED, f.manager.state.value.phase)
    }
    @Test fun wrongPinNeverStartsBiometricEnrollment() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "3210"); runCurrent()
        assertEquals("Неверный PIN-код", f.manager.state.value.error)
        assertEquals(1, f.storage.record!!.failedAttempts)
        assertEquals(0, f.biometrics.enrollments)
        assertNull(f.manager.prompt.value)
    }
    @Test fun keyPreparationFailureIsNotReportedAsWrongPin() = runTest {
        val f = fixture(); unlock(f)
        f.biometrics.enrollmentError = IllegalStateException("Неверный PIN-код")
        f.manager.setBiometric(true, "0123"); runCurrent()
        assertTrue(f.manager.state.value.error!!.startsWith("Не удалось подготовить биометрию"))
        assertEquals(0, f.storage.record!!.failedAttempts)
        assertFalse(f.manager.state.value.biometricEnabled)
        f.manager.disable("0123"); runCurrent()
        assertEquals(LockPhase.DISABLED, f.manager.state.value.phase)
    }
    @Test fun canceledBiometricsCanBeEnabledOnNextAttempt() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent()
        val old = f.manager.prompt.value!!
        f.manager.biometricError(old.cipher, null); runCurrent()
        assertTrue(f.biometrics.removed.contains(old.enrollmentAlias))
        assertNull(f.storage.record!!.biometric)
        f.manager.setBiometric(true, "0123"); runCurrent()
        val current = f.manager.prompt.value!!
        f.manager.biometricError(old.cipher, "late cancellation")
        f.manager.biometricResult(old.cipher); runCurrent()
        assertSame(current, f.manager.prompt.value)
        assertNull(f.manager.state.value.error)
        f.biometrics.authorized = true
        f.manager.biometricResult(current.cipher); runCurrent()
        assertTrue(f.manager.state.value.biometricEnabled)
    }
    @Test fun backgroundDuringKeyPreparationRemovesUnusedKey() = runTest {
        val f = fixture(); unlock(f)
        f.biometrics.onEnrolled = f.manager::background
        f.manager.setBiometric(true, "0123"); runCurrent()
        assertNull(f.manager.prompt.value)
        assertTrue(f.manager.state.value.allowsContent)
        assertNull(f.storage.record!!.biometric)
        assertEquals(1, f.biometrics.removed.size)
    }
    @Test fun successfulEnrollmentAllowsBiometricUnlock() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent()
        f.biometrics.authorized = true
        f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        f.manager.lock()
        f.manager.biometricUnlock(); runCurrent()
        assertFalse(f.manager.state.value.allowsContent)
        f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        assertTrue(f.manager.state.value.allowsContent)
        assertEquals(0, f.storage.record!!.failedAttempts)
    }
    @Test fun newPinNeedsCurrentAuthenticationAndProofExpiresInBackground() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertEquals("Введите текущий PIN-код", f.manager.state.value.error)
        f.manager.authorizePinChange("3210"); runCurrent()
        assertFalse(f.manager.state.value.pinChangeAuthorized)
        f.manager.authorizePinChange("0123"); runCurrent()
        assertTrue(f.manager.state.value.pinChangeAuthorized)
        f.manager.background(); f.manager.foreground()
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertEquals("Введите текущий PIN-код", f.manager.state.value.error)
        assertTrue(f.manager.state.value.allowsContent)
        assertTrue(f.storage.record!!.credential.verify("0123".toCharArray()))
    }
    @Test fun newPinIsSavedOnlyAfterCurrentPinAndMatchingRepeat() = runTest {
        val f = fixture(); unlock(f)
        f.manager.authorizePinChange("0123"); runCurrent()
        f.manager.setPin(null, "3456", "3457"); runCurrent()
        assertEquals("PIN-коды не совпадают", f.manager.state.value.error)
        assertTrue(f.storage.record!!.credential.verify("0123".toCharArray()))
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertFalse(f.manager.state.value.pinChangeAuthorized)
        assertTrue(f.storage.record!!.credential.verify("3456".toCharArray()))
        f.manager.lock(); f.manager.unlock("3456"); runCurrent()
        assertTrue(f.manager.state.value.allowsContent)
    }
    @Test fun pinChangeProofDoesNotAuthorizeBiometricChanges() = runTest {
        val f = fixture(); unlock(f)
        f.manager.authorizePinChange("0123"); runCurrent()
        f.manager.setBiometric(true, "3210"); runCurrent()
        assertEquals("Неверный PIN-код", f.manager.state.value.error)
        assertEquals(0, f.biometrics.enrollments)
    }
    @Test fun canceledPinChangeCannotSaveNewPin() = runTest {
        val f = fixture(); unlock(f)
        f.manager.authorizePinChange("0123"); runCurrent()
        f.manager.cancelPinChange()
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertEquals("Введите текущий PIN-код", f.manager.state.value.error)
        assertTrue(f.storage.record!!.credential.verify("0123".toCharArray()))
    }
    @Test fun initialPinRequiresMatchingRepeatAndSurvivesReload() = runTest {
        val f = fixture(null)
        f.manager.setPin(null, "0123", "0124"); runCurrent()
        assertNull(f.storage.value)
        f.manager.setPin(null, "0123", "0123"); runCurrent()
        assertTrue(f.manager.state.value.allowsContent)
        f.manager.reload(); runCurrent()
        assertEquals(LockPhase.LOCKED, f.manager.state.value.phase)
        unlock(f)
    }
    @Test fun legacyCredentialMustBeConfirmedBeforeNewPinIsSaved() = runTest {
        val f = fixture(AppLockRecord(password = digest, version = 1))
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertTrue(f.storage.record!!.needsPin)
        f.manager.unlock("0123"); runCurrent()
        assertTrue(f.manager.state.value.needsPinSetup)
        assertFalse(f.manager.state.value.allowsContent)
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertFalse(f.storage.record!!.needsPin)
        assertNull(f.storage.record!!.password)
        assertTrue(f.storage.record!!.credential.verify("3456".toCharArray()))
        assertTrue(f.manager.state.value.allowsContent)
    }
    @Test fun disposingPinEntryDoesNotEraseBiometricFailure() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent()
        f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        val error = f.manager.state.value.error
        assertTrue(error!!.startsWith("Не удалось включить биометрию"))
        f.manager.cancelPinChange()
        assertEquals(error, f.manager.state.value.error)
        assertNull(f.storage.record!!.biometric)
        assertEquals(0, f.storage.record!!.failedAttempts)
    }
    @Test fun sensitivePinNeedsRepeatProofAndClearsSeedAfterUse() = runTest {
        val f = fixture(); unlock(f)
        var captured: ByteArray? = null
        var denied = false
        val wrong = backgroundScope.launch { try { f.manager.sensitiveOperation("9999") { captured = it } } catch (_: Exception) { denied = true } }
        runCurrent(); assertTrue(denied); assertNull(captured); assertEquals(1, f.storage.record!!.failedAttempts)
        val correct = backgroundScope.launch { f.manager.sensitiveOperation("0123") { captured = it; assertTrue(it.any { b -> b != 0.toByte() }) } }
        runCurrent(); assertTrue(correct.isCompleted); assertTrue(captured!!.all { it == 0.toByte() }); assertEquals(0, f.storage.record!!.failedAttempts)
        assertNotNull(f.storage.record!!.sensitiveSeed)
    }
    @Test fun sensitiveBiometricRequiresANewCryptoPromptAndBackgroundCancelsIt() = runTest {
        val f = fixture(); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent(); f.biometrics.authorized = true
        f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        var called = false
        val view = backgroundScope.launch { f.manager.sensitiveOperation(null) { called = true } }
        runCurrent(); val pending = f.manager.prompt.value!!
        assertTrue(pending.sensitiveOperation); assertFalse(called)
        f.manager.background(); runCurrent(); f.manager.biometricResult(pending.cipher); runCurrent()
        assertTrue(view.isCancelled); assertFalse(called)
        f.manager.foreground(); unlock(f)
        var captured: ByteArray? = null
        val retry = backgroundScope.launch { f.manager.sensitiveOperation(null) { captured = it } }
        runCurrent(); f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        assertTrue(retry.isCompleted); assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test fun legacySeedMigratesWithoutChangingOperationKeyAndPinChangeKeepsBiometrics() = runTest {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val old = SensitivePinSeed.wrap("0123".toCharArray(), seed)
        val f = fixture(AppLockRecord(pin = digest, sensitiveSeed = old)); unlock(f)
        f.manager.setBiometric(true, "0123"); runCurrent()
        f.biometrics.authorized = true
        f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        assertTrue(f.storage.record!!.sensitiveSeed!!.keystoreWrapped)
        assertEquals(old.id, f.storage.record!!.sensitiveSeed!!.id)
        f.manager.authorizePinChange("0123"); runCurrent()
        f.manager.setPin(null, "3456", "3456"); runCurrent()
        assertEquals(old.id, f.storage.record!!.sensitiveSeed!!.id)
        val opened = f.seedProtection.unprotect(f.storage.record!!.sensitiveSeed!!)
        assertArrayEquals(seed, opened.open("3456".toCharArray()))
        assertThrows(Exception::class.java) { opened.open("0123".toCharArray()) }
        var captured: ByteArray? = null
        backgroundScope.launch { f.manager.sensitiveOperation(null) { captured = it; assertArrayEquals(seed, it) } }
        runCurrent(); f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
        assertTrue(captured!!.all { it == 0.toByte() })
        f.manager.disable("3456"); runCurrent()
        assertEquals(listOf(old.id), f.seedProtection.removed)
    }

    @Test fun lostDeviceWrappingKeyCannotAuthorizeSensitiveOperation() = runTest {
        val f = fixture(null)
        f.manager.setPin(null, "0123", "0123"); runCurrent()
        f.seedProtection.unavailable = true
        var actionCalled = false
        var denied = false
        backgroundScope.launch {
            try { f.manager.sensitiveOperation("0123") { actionCalled = true } }
            catch (_: Exception) { denied = true }
        }
        runCurrent(); assertTrue(denied); assertFalse(actionCalled)
        assertTrue(f.storage.record!!.sensitiveSeed!!.keystoreWrapped)
    }

    @Test fun activeSensitiveSeedIsErasedWhenAppGoesIntoBackground() = runTest {
        val f = fixture(); unlock(f)
        val wait = CompletableDeferred<Unit>()
        var captured: ByteArray? = null
        val operation = backgroundScope.launch { f.manager.sensitiveOperation("0123") { captured = it; wait.await() } }
        runCurrent(); assertTrue(captured!!.any { it != 0.toByte() })
        f.manager.background(); runCurrent()
        assertTrue(captured!!.all { it == 0.toByte() })
        wait.complete(Unit); runCurrent(); assertTrue(operation.isCompleted)
    }

    @Test fun returnsAtTenAndTwentyNineSecondsStayUnlockedButThirtyOneRequiresAuthentication() = runTest {
        for (seconds in listOf(10, 29, 31)) {
            val f = fixture(); unlock(f)
            f.manager.background(); runCurrent(); advanceTimeBy(seconds * 1000L); runCurrent()
            f.manager.foreground()
            assertEquals(if (seconds < 30) LockPhase.UNLOCKED else LockPhase.LOCKED, f.manager.state.value.phase)
        }
    }

    @Test fun anotherBackgroundTripRestartsCountdownAndColdStartNeverRestoresUnlock() = runTest {
        val f = fixture(); unlock(f)
        f.manager.background(); runCurrent(); advanceTimeBy(29_000); runCurrent(); f.manager.foreground()
        f.manager.background(); runCurrent(); advanceTimeBy(29_000); runCurrent(); f.manager.foreground()
        assertEquals(LockPhase.UNLOCKED, f.manager.state.value.phase)
        f.manager.background(); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(LockPhase.LOCKED, f.manager.state.value.phase)
        f.manager.foreground(); unlock(f)
        val restarted = fixture(f.storage.record)
        assertEquals(LockPhase.LOCKED, restarted.manager.state.value.phase)
    }

    @Test fun masterKeyConfirmationUsesOnePinOrBiometricGateBeforeTheNetworkRequest() = runTest {
        for (biometric in listOf(false, true)) {
            val f = fixture(); unlock(f)
            if (biometric) {
                f.manager.setBiometric(true, "0123"); runCurrent()
                f.biometrics.authorized = true
                f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
            }
            var requests = 0; var confirmations = 0; var seedRef: ByteArray? = null
            val viewer = MasterKeyViewer({ path, _ ->
                requests++
                buildJsonObject { put("ok", true); if (path.endsWith("challenge")) put("challenge_id", "one") else put("key", "secret") }
            })
            viewer.prepare(MasterKeyStep.LOCAL)
            val operation = backgroundScope.launch {
                viewer.authenticateLocal(authorize = { action ->
                    confirmations++; f.manager.sensitiveOperation(if (biometric) null else "0123", action)
                }, perform = { seed ->
                    seedRef = seed
                    viewer.local(signKeystore = { "native" }) { assertTrue(seed.any { it != 0.toByte() }); "proof" }
                })
            }
            runCurrent()
            if (biometric) {
                assertEquals(MasterKeyStep.LOCAL, viewer.state.value.step)
                f.manager.biometricResult(f.manager.prompt.value!!.cipher); runCurrent()
            }
            assertEquals(MasterKeyStep.READY, viewer.state.value.step)
            assertEquals(0, requests); assertNull(f.manager.prompt.value)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(MasterKeyStep.READY, viewer.state.value.step)
            viewer.approve(); runCurrent()
            assertTrue(operation.isCompleted); assertEquals(1, confirmations); assertEquals(2, requests)
            assertNull(f.manager.prompt.value); assertEquals("secret", viewer.state.value.key)
            assertTrue(seedRef!!.all { it == 0.toByte() })
            f.manager.background(); viewer.background(); runCurrent()
            assertEquals(LockPhase.UNLOCKED, f.manager.state.value.phase)
            assertNull(viewer.state.value.key)
        }
    }

}
