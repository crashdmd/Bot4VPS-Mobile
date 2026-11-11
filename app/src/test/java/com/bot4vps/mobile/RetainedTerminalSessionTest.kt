package com.bot4vps.mobile

import com.bot4vps.mobile.ui.terminal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedTerminalSessionTest {
    private class Connection : TerminalConnection {
        val sent = mutableListOf<String>()
        var closes = 0
        override fun send(message: String): Boolean { sent.add(message); return true }
        override fun close() { closes++ }
    }
    private class Fixture(val scope: TestScope) {
        val owner = Any()
        val connections = mutableListOf<Connection>()
        val listeners = mutableListOf<TerminalListener>()
        var clock: (() -> Long) = { scope.testScheduler.currentTime }
        val session = RetainedTerminalSession(scope, { listener ->
            listeners.add(listener)
            Connection().also { connections.add(it); listener.opened() }
        }, { clock() })
        fun start() { session.resume(owner); scope.runCurrent() }
    }

    @Test fun returningBeforeFiveMinutesReusesTheSocketAndBackgroundOutput() = runTest {
        val f = Fixture(this); f.start()
        f.listeners.single().received("before\r\n")
        f.session.pause(f.owner)
        advanceTimeBy(299_999); runCurrent()
        f.listeners.single().received("while away\r\n")
        f.session.resume(f.owner); runCurrent()
        assertEquals(1, f.connections.size); assertEquals(0, f.connections.single().closes)
        assertTrue(f.session.buffer.lines().any { it.contains("before") })
        assertTrue(f.session.buffer.lines().any { it.contains("while away") })
        f.session.send("pwd\r")
        assertTrue(f.connections.single().sent.last().contains("pwd"))
        f.session.close()
    }

    @Test fun expiryClosesExactlyOnceAndReopeningCreatesANewSession() = runTest {
        val f = Fixture(this); f.start(); f.listeners.single().received("old output")
        f.session.pause(f.owner); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, f.connections.single().closes)
        assertTrue(f.session.buffer.lines().all { it.isBlank() })
        assertTrue(f.session.status.value.message.contains("5 минут"))
        f.session.resume(f.owner); runCurrent()
        assertEquals(2, f.connections.size)
        assertTrue(f.session.status.value.message.contains("новая сессия"))
        f.session.close()
    }

    @Test fun duplicatePauseFromStopAndDisposalDoesNotExtendTheDeadline() = runTest {
        val f = Fixture(this); f.start(); f.session.pause(f.owner); runCurrent()
        advanceTimeBy(200_000); f.session.pause(f.owner); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, f.connections.single().closes)
        f.session.close()
    }

    @Test fun visibleTerminalDoesNotExpireAfterFiveMinutes() = runTest {
        val f = Fixture(this); f.start()
        advanceTimeBy(900_000); runCurrent()
        assertEquals(0, f.connections.single().closes)
        assertEquals("Подключено", f.session.status.value.message)
        f.session.close()
    }

    @Test fun returningCancelsTheOldTimerAndNextDepartureStartsANewPeriod() = runTest {
        val f = Fixture(this); f.start(); f.session.pause(f.owner); runCurrent()
        advanceTimeBy(200_000); f.session.resume(f.owner); runCurrent()
        advanceTimeBy(200_000); runCurrent()
        assertEquals(0, f.connections.single().closes)
        f.session.pause(f.owner); runCurrent(); advanceTimeBy(300_000); runCurrent()
        assertEquals(1, f.connections.single().closes)
        f.session.close()
    }

    @Test fun deepSleepCannotExtendRetentionWhenTheTimerHasNotRun() = runTest {
        val f = Fixture(this); var elapsed = 0L; f.clock = { elapsed }
        f.start(); f.session.pause(f.owner); runCurrent()
        elapsed = 300_001 // Android was asleep; the coroutine scheduler did not advance.
        f.session.resume(f.owner); runCurrent()
        assertEquals(1, f.connections.first().closes)
        assertEquals(2, f.connections.size)
        f.session.close()
    }

    @Test fun backgroundOrLockedScreenCannotSendInput() = runTest {
        val f = Fixture(this); f.start()
        fun inputCount() = f.connections.single().sent.count { it.contains("\"type\":\"input\"") }
        val count = inputCount()
        f.session.pause(f.owner); f.session.send("rm anything\r")
        assertEquals(count, inputCount())
        f.session.resume(f.owner); f.session.send("pwd\r")
        assertEquals(count + 1, inputCount())
        f.session.close()
    }

    @Test fun logoutOrPanelChangeInvalidatesAllFutureCallbacksAndResumes() = runTest {
        val f = Fixture(this); f.start(); f.listeners.single().received("secret terminal output")
        f.session.invalidate()
        f.session.resume(f.owner); f.session.connect(); f.session.send("pwd\r")
        f.listeners.single().opened(); f.listeners.single().received("late output")
        f.listeners.single().closed("late failure"); runCurrent()
        assertEquals(1, f.connections.size); assertEquals(1, f.connections.single().closes)
        assertTrue(f.session.buffer.lines().all { it.isBlank() })
        assertFalse(f.session.status.value.canConnect)
    }

    @Test fun repeatedResumeAndConnectDoNotOpenDuplicateSockets() = runTest {
        var opens = 0
        val connection = Connection()
        val session = RetainedTerminalSession(this, { listener ->
            opens++; delay(500); listener.opened(); connection
        }, { testScheduler.currentTime })
        val owner = Any()
        session.resume(owner); session.resume(owner); session.connect(); runCurrent()
        assertEquals(1, opens)
        advanceTimeBy(500); runCurrent(); session.connect()
        assertEquals(1, opens); session.close()
    }

    @Test fun aLateConnectionResultIsClosedAfterInvalidation() = runTest {
        val connection = Connection()
        var listener: TerminalListener? = null
        val session = RetainedTerminalSession(this, { callback ->
            listener = callback
            try { delay(100_000) } catch (_: CancellationException) { /* Simulate a late transport result. */ }
            connection
        }, { testScheduler.currentTime })
        session.resume(Any()); runCurrent(); session.invalidate(); runCurrent()
        listener!!.opened(); listener!!.received("late output")
        assertEquals(1, connection.closes)
        assertFalse(session.status.value.canConnect)
        assertTrue(session.buffer.lines().all { it.isBlank() })
    }

    @Test fun connectionFailureKeepsTheBufferUntilExplicitReconnectAndRejectsLateOutput() = runTest {
        val f = Fixture(this); f.start(); f.listeners.single().received("previous command")
        f.listeners.single().closed("Сеть недоступна")
        assertTrue(f.session.status.value.canConnect)
        f.listeners.single().received("must not appear")
        assertTrue(f.session.buffer.lines().any { it.contains("previous command") })
        assertFalse(f.session.buffer.lines().any { it.contains("must not appear") })
        f.session.connect(); runCurrent(); assertEquals(2, f.connections.size)
        f.session.close()
    }

    @Test fun pendingHandshakeHasATenSecondDeadline() = runTest {
        val connection = Connection()
        val session = RetainedTerminalSession(this, { connection }, { testScheduler.currentTime })
        session.resume(Any()); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertTrue(session.status.value.canConnect)
        assertTrue(session.status.value.message.contains("10 секунд"))
        assertEquals(1, connection.closes); session.close()
    }

    @Test fun multipleOwnersKeepTheConnectionUntilTheLastOneLeaves() = runTest {
        val f = Fixture(this); f.start(); val second = Any(); f.session.resume(second)
        f.session.pause(f.owner); runCurrent(); advanceTimeBy(400_000); runCurrent()
        assertEquals(0, f.connections.single().closes)
        f.session.pause(second); runCurrent(); advanceTimeBy(300_000); runCurrent()
        assertEquals(1, f.connections.single().closes); f.session.close()
    }

    @Test fun terminalBufferRemainsBoundedAndResetClearsTheParserState() = runTest {
        val f = Fixture(this); f.start()
        repeat(10_000) { f.listeners.single().received("line $it\r\n") }
        assertEquals(24, f.session.buffer.lines().size)
        f.session.buffer.append("\u001b["); f.session.close()
        f.session.buffer.append("hello")
        assertEquals("hello", f.session.buffer.lines().first())
    }

    @Test fun outputAfterTheElapsedDeadlineClosesTheSessionEvenBeforeTheTimerRuns() = runTest {
        val f = Fixture(this); var elapsed = 0L; f.clock = { elapsed }
        f.start(); f.session.pause(f.owner); runCurrent()
        elapsed = 300_001
        f.listeners.single().received("late output")
        assertEquals(1, f.connections.single().closes)
        assertTrue(f.session.buffer.lines().all { it.isBlank() })
        assertTrue(f.session.status.value.message.contains("5 минут"))
        f.session.close()
    }

    @Test fun returningAfterViewRecreationResizesTheExistingRemoteTerminal() = runTest {
        val f = Fixture(this); f.start(); f.session.pause(f.owner)
        val count = f.connections.single().sent.size
        f.session.resize(100, 36)
        assertEquals(count, f.connections.single().sent.size)
        f.session.resume(f.owner)
        assertEquals(1, f.connections.size)
        val resize = Json.parseToJsonElement(f.connections.single().sent.last()).jsonObject
        assertEquals("resize", resize["type"]!!.jsonPrimitive.content)
        assertEquals(100, resize["cols"]!!.jsonPrimitive.int)
        assertEquals(36, resize["rows"]!!.jsonPrimitive.int)
        f.session.close()
    }

    @Test fun aLateHandshakeCannotOpenAnExpiredBackgroundSession() = runTest {
        var elapsed = 0L
        val connection = Connection()
        lateinit var listener: TerminalListener
        val session = RetainedTerminalSession(this, { listener = it; connection }, { elapsed })
        val owner = Any(); session.resume(owner); runCurrent(); session.pause(owner); runCurrent()
        elapsed = 300_001; listener.opened()
        assertEquals(1, connection.closes)
        assertTrue(session.status.value.message.contains("5 минут"))
        session.close()
    }
}
