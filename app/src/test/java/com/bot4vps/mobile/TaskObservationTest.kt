package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.ApiError
import com.bot4vps.mobile.data.model.*
import com.bot4vps.mobile.data.repository.observeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class TaskObservationTest {
    @Test fun missingTaskStopsBothHttpClientsAndPreservesAvailableOutput() = runTest {
        for (error in listOf(ApiError(404, "not found"), HttpException(Response.error<Any>(404, "missing".toResponseBody())))) {
            var calls = 0
            var task = TaskSummary("accepted", status = "running", outputLines = listOf("Operation started"))
            repeat(3) { task = observeTask(task) { calls++; throw error } }
            assertEquals(1, calls)
            assertTrue(task.unavailable)
            assertFalse(task.isDone)
            assertFalse(task.canPoll)
            assertEquals(listOf("Operation started"), task.outputLines)
        }
    }

    @Test fun temporaryFailureCanRecoverWithoutResubmittingOperation() = runTest {
        val task = TaskSummary("accepted", status = "running")
        for (error in listOf(IOException("offline"), ApiError(503, "busy"), HttpException(Response.error<Any>(500, "busy".toResponseBody())), CancellationException("closed"))) {
            assertSame(error, runCatching { observeTask(task) { throw error } }.exceptionOrNull())
            assertTrue(task.canPoll)
            assertFalse(task.unavailable)
        }
        val finished = observeTask(task) { id -> assertEquals("accepted", id); task.copy(status = "success_warn", isDone = true, result = TaskResult(success = true, output = "Очистка завершена с замечаниями")) }
        assertFalse(finished.canPoll)
        assertEquals("success_warn", finished.status)
        assertTrue(finished.result!!.output!!.contains("замечаниями"))
    }
}
