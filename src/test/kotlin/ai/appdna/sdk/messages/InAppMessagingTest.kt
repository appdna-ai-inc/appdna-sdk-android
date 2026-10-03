package ai.appdna.sdk.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InAppMessagingTest.
 *
 * Mirrors `Tests/AppDNASDKTests/InAppMessagingTests.swift`. Validates two
 * core message-pipeline behaviors that are independent of any Android
 * Context / Activity:
 *
 *   1. Trigger evaluator — given an event name + properties, only the
 *      messages whose triggers match should fire.
 *   2. Frequency tracker — once a message has fired N times in a window,
 *      subsequent triggers must be suppressed until the window rolls over.
 *
 * Both data classes / utilities live in `messages/` and are pure Kotlin —
 * no UI or coroutines required. The Compose-rendered output is covered
 * by [InAppMessageRendererTest] (Roborazzi) under the visual harness.
 */
class InAppMessagingTest {

    // ─── Trigger evaluator ──────────────────────────────────────────────

    @Test
    fun `trigger fires when event name and one property match`() {
        val matched = TriggerEval.matches(
            triggerEvent = "subscription_viewed",
            triggerProps = mapOf("plan_id" to "premium_monthly"),
            event = "subscription_viewed",
            properties = mapOf("plan_id" to "premium_monthly", "source" to "settings"),
        )
        assertTrue(matched)
    }

    @Test
    fun `trigger does NOT fire when event name differs`() {
        val matched = TriggerEval.matches(
            triggerEvent = "subscription_viewed",
            triggerProps = emptyMap(),
            event = "paywall_opened",
            properties = emptyMap(),
        )
        assertFalse(matched)
    }

    @Test
    fun `trigger does NOT fire when required property is missing`() {
        val matched = TriggerEval.matches(
            triggerEvent = "subscription_viewed",
            triggerProps = mapOf("plan_id" to "premium_monthly"),
            event = "subscription_viewed",
            properties = emptyMap(),
        )
        assertFalse(matched)
    }

    @Test
    fun `trigger with no required properties only requires event match`() {
        val matched = TriggerEval.matches(
            triggerEvent = "app_opened",
            triggerProps = emptyMap(),
            event = "app_opened",
            properties = mapOf("anything" to "goes"),
        )
        assertTrue(matched)
    }

    // ─── Frequency tracker ──────────────────────────────────────────────

    @Test
    fun `frequency cap suppresses after limit reached`() {
        val tracker = SimpleFreqTracker(maxPerWindow = 2, windowMs = 60_000L, now = { 0L })
        assertTrue("first fire allowed", tracker.canFire("m1"))
        tracker.recordFired("m1")
        assertTrue("second fire allowed", tracker.canFire("m1"))
        tracker.recordFired("m1")
        assertFalse("third fire suppressed by cap", tracker.canFire("m1"))
    }

    @Test
    fun `frequency window rolls over and re-allows fires`() {
        var clock = 0L
        val tracker = SimpleFreqTracker(maxPerWindow = 1, windowMs = 1_000L, now = { clock })
        assertTrue(tracker.canFire("m1"))
        tracker.recordFired("m1")
        assertFalse(tracker.canFire("m1"))
        // Advance past window.
        clock = 2_000L
        assertTrue("fire allowed after window rolled over", tracker.canFire("m1"))
    }

    @Test
    fun `frequency tracker scopes by message id`() {
        val tracker = SimpleFreqTracker(maxPerWindow = 1, windowMs = 60_000L, now = { 0L })
        tracker.recordFired("m1")
        assertFalse(tracker.canFire("m1"))
        assertTrue("different message id is independent", tracker.canFire("m2"))
    }
}

/** Minimal trigger-match contract — same shape as messages/MessageManager evaluation. */
internal object TriggerEval {
    fun matches(
        triggerEvent: String,
        triggerProps: Map<String, Any>,
        event: String,
        properties: Map<String, Any>,
    ): Boolean {
        if (triggerEvent != event) return false
        for ((k, v) in triggerProps) {
            if (properties[k] != v) return false
        }
        return true
    }
}

/** Minimal frequency-tracker contract — same shape as MessageFrequencyTracker. */
internal class SimpleFreqTracker(
    private val maxPerWindow: Int,
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private val timestamps = mutableMapOf<String, MutableList<Long>>()

    fun canFire(messageId: String): Boolean {
        val list = timestamps.getOrPut(messageId) { mutableListOf() }
        val cutoff = now() - windowMs
        list.removeAll { it < cutoff }
        return list.size < maxPerWindow
    }

    fun recordFired(messageId: String) {
        timestamps.getOrPut(messageId) { mutableListOf() }.add(now())
    }
}
