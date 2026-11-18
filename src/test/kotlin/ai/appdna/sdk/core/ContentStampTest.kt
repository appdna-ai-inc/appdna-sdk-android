package ai.appdna.sdk.core

import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC-536: the four served models read `version` and `served_from` through their REAL parsers, an old
 * config (no `served_from`, and for messages and surveys no `version`) still parses with both absent, and the
 * one helper writes `content_source_id` only for a swapped item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentStampTest {

    private val src = mapOf("id" to "src_1", "version" to 3L)

    private fun flow(extra: Map<String, Any>) = OnboardingConfigParser.parseSingleFlow(
        "flow_a",
        mapOf("id" to "flow_a", "name" to "A", "version" to 7L, "settings" to emptyMap<String, Any>(),
            "steps" to listOf(mapOf("id" to "s1", "type" to "welcome", "config" to mapOf("title" to "Hi")))) + extra,
    )!!

    private fun paywall(extra: Map<String, Any>) = PaywallConfigParser.parseSinglePaywall(
        "pw_a", mapOf("id" to "pw_a", "name" to "A", "layout" to mapOf("type" to "stack"), "sections" to emptyList<Any>()) + extra,
    )!!

    private fun message(extra: Map<String, Any>) = MessageConfigParser.parseSingleMessage(
        mapOf("name" to "m", "message_type" to "modal", "content" to mapOf("title" to "T"),
            "trigger_rules" to mapOf("event" to "e")) + extra,
    )!!

    private fun survey(extra: Map<String, Any>) = SurveyConfig.fromMap(
        mapOf("name" to "s", "survey_type" to "custom", "questions" to emptyList<Any>(),
            "trigger_rules" to mapOf("event" to "e"), "appearance" to emptyMap<String, Any>()) + extra,
    )!!

    private val swapped = mapOf("content_version" to 7, "content_source_id" to "src_1")
    private val plain = mapOf<String, Any>("content_version" to 7)

    @Test
    fun `a swapped item on each surface stamps both keys`() {
        val extra = mapOf("version" to 7L, "served_from" to src)
        assertEquals(swapped, flow(mapOf("served_from" to src)).contentStamp)
        assertEquals(swapped, paywall(extra).contentStamp)
        assertEquals(swapped, message(extra).contentStamp)
        assertEquals(swapped, survey(extra).contentStamp)
        assertEquals(ServedFrom("src_1", 3), paywall(extra).served_from)
    }

    @Test
    fun `an item that is not swapped stamps only content_version`() {
        val extra = mapOf<String, Any>("version" to 7L)
        assertEquals(plain, flow(emptyMap()).contentStamp)
        assertEquals(plain, paywall(extra).contentStamp)
        assertEquals(plain, message(extra).contentStamp)
        assertEquals(plain, survey(extra).contentStamp)
    }

    @Test
    fun `an old config still parses, with no served_from and, for messages and surveys, no version`() {
        assertNull(flow(emptyMap()).served_from)
        assertNull(paywall(emptyMap()).served_from)
        assertNull(paywall(emptyMap()).version)
        val m = message(emptyMap())
        assertNull(m.version); assertNull(m.served_from)
        assertEquals(emptyMap<String, Any>(), m.contentStamp)
        val s = survey(emptyMap())
        assertNull(s.version); assertNull(s.servedFrom)
        assertEquals(emptyMap<String, Any>(), s.contentStamp)
    }

    @Test
    fun `a served_from with no usable id is absent, never a blank source`() {
        assertNull(ServedFrom.parse(mapOf("id" to "", "version" to 3)))
        assertNull(ServedFrom.parse(mapOf("version" to 3)))
        assertNull(ServedFrom.parse("src_1"))
        assertEquals(ServedFrom("src_1", null), ServedFrom.parse(mapOf("id" to "src_1")))
    }

    @Test
    fun `the stamp wins any collision, and paywall custom data may not carry either key`() {
        assertEquals(swapped + ("x" to 1), ContentStamp.apply(mapOf("content_version" to 99, "x" to 1), swapped))
        assertTrue(PaywallContext.RESERVED_EVENT_KEYS.containsAll(ContentStamp.KEYS))
        assertTrue(PaywallContext.RESERVED_EVENT_KEYS.containsAll(setOf("paywall_id", "placement")))
    }
}
