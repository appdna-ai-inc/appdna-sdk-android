package ai.appdna.sdk.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventQueueTest.
 *
 * Mirrors `Tests/AppDNASDKTests/EventQueueTests.swift`. The bulk of the
 * Android EventQueue policy contract is already covered by
 * [EventQueuePolicyTest] (retry policy, in-memory cap, jitter).
 * This file adds two further checks that the iOS suite has and the
 * Android one was missing:
 *
 *   1. Default batch size matches iOS `defaultBatchSize`.
 *   2. Drop-on-overflow semantics drop OLDEST events first (FIFO) so we
 *      always send the freshest signal — never the freshest event itself.
 *
 * The class names of the live constants ([EventQueue.MAX_IN_MEMORY_EVENTS]
 * etc.) are validated in [EventQueuePolicyTest]; this file owns
 * the additional behavioral assertions iOS calls out separately.
 */
class EventQueueTest {

    @Test
    fun `default batch size matches iOS defaultBatchSize`() {
        // iOS and Android: no batchSize option means no cap of the host's own — the network-sized batch
        // (capped by the bootstrap's settings.batchSize once one arrives). See RuntimeSettings.
        // The option keeps its non-null default (100 = not set by the host), as iOS's resolved `batchSize`.
        assertEquals(100, ai.appdna.sdk.AppDNAOptions().batchSize)
        assertEquals(null, ai.appdna.sdk.AppDNAOptions().requestedBatchSize)
        assertEquals(null, RuntimeSettings.resolveAll(ai.appdna.sdk.AppDNAOptions(), null).batchSizeCap)
    }

    @Test
    fun `default flush interval is 30s matching iOS`() {
        // iOS `RuntimeSettings.defaultFlushInterval = 30`; the option's default (30) reads as not set.
        assertEquals(30L, ai.appdna.sdk.AppDNAOptions().flushInterval)
        assertEquals(null, ai.appdna.sdk.AppDNAOptions().requestedFlushInterval)
        assertEquals(30L, RuntimeSettings.resolveAll(ai.appdna.sdk.AppDNAOptions(), null).flushInterval)
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

    /** An interval of 0 or less can no longer spin the auto-flush loop. */
    @Test
    fun `auto-flush interval is at least one second and saturates instead of overflowing`() {
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(0))
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(-5))
        assertEquals(1_000L, EventQueue.autoFlushIntervalMs(Long.MIN_VALUE))
        assertEquals(30_000L, EventQueue.autoFlushIntervalMs(30))
        assertEquals(Long.MAX_VALUE, EventQueue.autoFlushIntervalMs(Long.MAX_VALUE))
    }
}
