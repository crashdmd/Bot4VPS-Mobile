package com.bot4vps.mobile

import com.bot4vps.mobile.data.local.*
import com.bot4vps.mobile.data.model.MobileRelease
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MobileUpdateManagerTest {
    private class Preferences : AppUpdatePreferences {
        override val appAutoUpdate = MutableStateFlow(true)
        override val appUpdateCheckAt = MutableStateFlow(0L)
        override suspend fun setAppAutoUpdate(value: Boolean) { appAutoUpdate.value = value }
        override suspend fun rememberAppUpdateCheck(value: Long) { appUpdateCheckAt.value = value }
    }
    private class Updater(val attempt: suspend () -> MobileRelease?) : AppReleaseUpdater {
        var calls = 0
        override suspend fun check(): MobileRelease? { calls++; return attempt() }
        override suspend fun download(release: MobileRelease, onProgress: (Int) -> Unit): File = error("Not requested")
    }

    @Test fun failedAutomaticCheckRetriesWithoutWaitingADay() = runTest {
        val prefs = Preferences()
        var failed = true
        val updater = Updater { if (failed) error("Нет соединения") else null }
        val manager = MobileUpdateManager(prefs, updater, backgroundScope) { 200_000_000L }
        manager.check(manual = false); runCurrent()
        assertEquals(0L, prefs.appUpdateCheckAt.value)
        assertEquals("Нет соединения", manager.state.value.error)
        failed = false
        manager.check(manual = false); runCurrent()
        assertEquals(2, updater.calls)
        assertEquals(200_000_000L, prefs.appUpdateCheckAt.value)
        assertNull(manager.state.value.error)
        manager.check(manual = false); runCurrent()
        assertEquals(2, updater.calls)
    }

    @Test fun onlyAFinishedRequestAdvancesTheTimestampAndManualCheckBypassesTheInterval() = runTest {
        val prefs = Preferences()
        val reply = CompletableDeferred<MobileRelease?>()
        val updater = Updater { reply.await() }
        var time = 200_000_000L
        val manager = MobileUpdateManager(prefs, updater, backgroundScope) { time }
        manager.check(manual = false); runCurrent()
        assertTrue(manager.state.value.checking)
        assertEquals(0L, prefs.appUpdateCheckAt.value)
        manager.check(); runCurrent(); assertEquals(1, updater.calls)
        time += 1000
        reply.complete(null); runCurrent()
        assertEquals(time, prefs.appUpdateCheckAt.value)
        assertFalse(manager.state.value.checking)
        manager.check(); runCurrent()
        assertEquals(2, updater.calls)
        assertEquals("Новых версий приложения нет", manager.state.value.notice)
    }

    @Test fun disabledAutomaticChecksAndCancelledRequestsNeverAdvanceTheTimestamp() = runTest {
        val prefs = Preferences().apply { appAutoUpdate.value = false }
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val updater = Updater { awaitCancellation() }
        val manager = MobileUpdateManager(prefs, updater, owner) { 200_000_000L }
        manager.check(manual = false); runCurrent(); assertEquals(0, updater.calls)
        manager.check(); runCurrent(); assertEquals(1, updater.calls)
        owner.cancel(); runCurrent()
        assertEquals(0L, prefs.appUpdateCheckAt.value)
        assertFalse(manager.state.value.checking)
        assertNull(manager.state.value.error)
    }
}
