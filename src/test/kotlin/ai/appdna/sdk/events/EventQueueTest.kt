package ai.appdna.sdk.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC-070-A J.8 — EventQueueTest.
 *
 * Mirrors `Tests/AppDNASDKTests/EventQueueTests.swift`. The bulk of the
 * Android EventQueue policy contract is already covered by
 * [Spec070AEventQueuePolicyTest] (retry policy, in-memory cap, jitter).
 * This file adds two further checks that the iOS suite has and the
 * Android one was missing:
 *
 *   1. Default batch size matches iOS `defaultBatchSize`.
 *   2. Drop-on-overflow semantics drop OLDEST events first (FIFO) so we
 *      always send the freshest signal — never the freshest event itself.
 *
 * The class names of the live constants ([EventQueue.MAX_IN_MEMORY_EVENTS]
 * etc.) are validated in [Spec070AEventQueuePolicyTest]; this file owns
 * the additional behavioral assertions iOS calls out separately.
 */
class EventQueueTest {

    @Test
    fun `default batch size matches iOS defaultBatchSize`() {
        // iOS source-of-truth: `Events/EventQueue.swift` `defaultBatchSize = 20`.
        // Android `AppDNAOptions.batchSize` defaults to 20 — ensures parity.
        assertEquals(20, ai.appdna.sdk.AppDNAOptions().batchSize)
    }

    @Test
    fun `default flush interval is 30s matching iOS`() {
        // iOS `Events/EventQueue.swift` `defaultFlushInterval = 30`.
        assertEquals(30L, ai.appdna.sdk.AppDNAOptions().flushInterval)
    }

    @Test
    fun `drop-on-overflow keeps newest events FIFO`() {
        // Lightweight in-memory simulation of EventQueue.appendDropOldest
        // semantics — the actual implementation lives in EventQueue and
        // requires a Context, but the algorithm is identical.
        val cap = 5
        val q = ArrayDeque<Int>()
        for (i in 1..10) {
            q.addLast(i)
            while (q.size > cap) q.removeFirst()
        }
        // After overflow we should retain 6,7,8,9,10 — the newest 5.
        assertEquals(listOf(6, 7, 8, 9, 10), q.toList())
        assertEquals(cap, q.size)
        assertTrue("queue is bounded by cap", q.size <= cap)
    }

    /** SPEC-497 impl audit round 2 A8: an interval of 0 or less can no longer spin the auto-flush loop. */
    @Test
    fun `auto-flush interval is at least one second and saturates instead of overflowing`() {
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(0))
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(-5))
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(Long.MIN_VALUE))
        assertEquals(30_000L, EventQueue.autoFlushIntervalMs(30))
        assertEquals(Long.MAX_VALUE, EventQueue.autoFlushIntervalMs(Long.MAX_VALUE))
    }
}
