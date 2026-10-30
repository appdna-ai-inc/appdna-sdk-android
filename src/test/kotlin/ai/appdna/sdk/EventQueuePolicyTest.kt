package ai.appdna.sdk

import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.network.EventUploadResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verify the EventQueue retry/backoff/cap contract.
 *
 * These tests intentionally validate constants + the EventUploadResult sealed
 * class shape so we can guarantee parity with the iOS implementation without
 * having to spin up an Android Context (ProcessLifecycleOwner) for the
 * full integration test, which is impossible to do in a pure-JVM unit test.
 *
 * iOS counterparts (source-of-truth):
 *   - `Events/EventQueue.swift` `maxInMemoryEvents = 1000`
 *   - `Events/EventQueue.swift` `maxRetries = 3`, `retryDelays = [1, 2, 4]`
 *   - `Events/EventQueue.swift` `maxConsecutiveFailures = 5`
 */
class EventQueuePolicyTest {

    // MARK: - A.17 In-memory cap

    @Test
    fun `A_17 in-memory cap matches iOS maxInMemoryEvents`() {
        // The iOS source-of-truth is Events/EventQueue.swift `maxInMemoryEvents = 1000`.
        // Drift here means Android will OOM under load that iOS handles fine.
        assertEquals(1000, EventQueue.MAX_IN_MEMORY_EVENTS)
    }

    // MARK: - A.16 Retry policy

    @Test
    fun `A_16 max retries matches iOS maxRetries`() {
        assertEquals(3, EventQueue.MAX_RETRIES)
    }

    @Test
    fun `A_16 retry delays are 1s 2s 4s (matches iOS retryDelays)`() {
        // iOS Events/EventQueue.swift: retryDelays: [TimeInterval] = [1, 2, 4]
        assertArrayEquals(longArrayOf(1000L, 2000L, 4000L), EventQueue.RETRY_DELAYS_MS)
    }

    @Test
    fun `A_16 jitter percentage is 25%`() {
        // The prompt requires ±25% jitter to avoid retry-storm thundering herds.
        assertEquals(0.25, EventQueue.JITTER_PCT, 0.0001)
    }

    @Test
    fun `A_16 pause threshold is 5 consecutive failures`() {
        // Matches iOS Events/EventQueue.swift `maxConsecutiveFailures = 5`.
        assertEquals(5, EventQueue.MAX_CONSECUTIVE_FAILURES)
    }

    // MARK: - A.16 EventUploadResult differentiation

    @Test
    fun `A_16 EventUploadResult Success is the success outcome`() {
        val r: EventUploadResult = EventUploadResult.Success
        assertTrue(r is EventUploadResult.Success)
        assertFalse(r is EventUploadResult.ClientError)
        assertFalse(r is EventUploadResult.TransientFailure)
    }

    @Test
    fun `A_16 EventUploadResult ClientError carries 4xx status code`() {
        val r = EventUploadResult.ClientError(statusCode = 400)
        assertEquals(400, r.statusCode)
        // A second instance with the same status must be data-class equal.
        assertEquals(r, EventUploadResult.ClientError(400))
    }

    @Test
    fun `A_16 EventUploadResult TransientFailure may carry null status (network error)`() {
        val r = EventUploadResult.TransientFailure(statusCode = null)
        assertNotNull(r)
        assertEquals(null, r.statusCode)
    }

    @Test
    fun `A_16 EventUploadResult TransientFailure carries 5xx and 429 status codes`() {
        val r1 = EventUploadResult.TransientFailure(statusCode = 503)
        val r2 = EventUploadResult.TransientFailure(statusCode = 429)
        val r3 = EventUploadResult.TransientFailure(statusCode = 500)
        assertEquals(503, r1.statusCode)
        assertEquals(429, r2.statusCode)
        assertEquals(500, r3.statusCode)
    }
}
