package com.healthanalyzer.app.ai

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException

class RetryBeforeFirstTokenTest {

    @Test fun retriesAbortBeforeAnyOutput() = runBlocking {
        var calls = 0
        val out = flow {
            calls++
            if (calls == 1) throw SocketException("Software caused connection abort")
            emit("hi")
        }.retryBeforeFirstToken(backoffMs = 0).toList()
        assertEquals(listOf("hi"), out)
        assertEquals(2, calls)
    }

    @Test fun dropMidAnswerBecomesReadableError() = runBlocking {
        val got = mutableListOf<String>()
        try {
            flow {
                emit("partial")
                throw SocketException("Software caused connection abort")
            }.retryBeforeFirstToken(backoffMs = 0).collect { got += it }
            fail("expected an error")
        } catch (e: NetworkException) {
            assertTrue(e.message!!.contains("before the answer finished"))
        }
        assertEquals(listOf("partial"), got)
    }

    @Test fun wrappedIoCauseCountsAsNetwork() {
        assertTrue(RuntimeException("sdk", SocketException("reset")).isTransientNetworkError())
        assertTrue(!SocketTimeoutException("timeout").isTransientNetworkError())
        assertTrue(!LlmException("HTTP 401").isTransientNetworkError())
    }

    @Test fun httpErrorsAreNotRetried() = runBlocking {
        var calls = 0
        try {
            flow<String> { calls++; throw LlmException("HTTP 401: bad key") }.retryBeforeFirstToken(backoffMs = 0).toList()
            fail("expected an error")
        } catch (e: LlmException) {
            assertEquals("HTTP 401: bad key", e.message)
        }
        assertEquals(1, calls)
    }

    @Test fun givesUpAfterRetries() = runBlocking {
        var calls = 0
        try {
            flow<String> { calls++; throw SocketException("abort") }.retryBeforeFirstToken(retries = 2, backoffMs = 0).toList()
            fail("expected an error")
        } catch (e: NetworkException) {
            assertTrue(e.message!!.contains("dropped"))
        }
        assertEquals(3, calls)
    }
}
