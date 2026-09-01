package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The flag CTA's string plumbing, driven through the REAL functions the shipping path uses.
 *
 * This exists because the two halves used to be unreachable from a JVM test: the encode was an
 * expression inside `ContentBlockRenderer`'s onClick lambda and the decode was inside
 * `@Composable`-scoped `OnboardingActivity.handleAction`. `permission` had already shipped with the
 * encode missing — a per-CTA permission type resolved to null and only the step-level one worked —
 * and nothing caught it, because the only test covering that dispatch MIRRORS the table rather than
 * calling it. Both halves are now `OnboardingActionPair`, and these tests call them directly.
 *
 * The round-trip assertions are the point: a flag authored in the Console must survive
 * `encode -> onAction(String) -> decode -> parse` and come out as the same key/value pair.
 */
class OnboardingCTAFlagTest {

    // MARK: - parse

    @Test
    fun `a bare key records the string true`() {
        val flag = OnboardingCTAFlag.parse("wants_callback")
        assertEquals("wants_callback", flag?.key)
        // A String, not a Boolean: this value crosses to Flutter and React Native through JSON and
        // lands in analytics props, and a String is the one representation all four agree on.
        assertEquals("true", flag?.value)
    }

    @Test
    fun `key equals value splits on the first equals only`() {
        val flag = OnboardingCTAFlag.parse("upsell_choice=booking")
        assertEquals("upsell_choice", flag?.key)
        assertEquals("booking", flag?.value)
    }

    @Test
    fun `a value may itself contain an equals sign`() {
        val flag = OnboardingCTAFlag.parse("utm=a=b")
        assertEquals("utm", flag?.key)
        assertEquals("a=b", flag?.value)
    }

    @Test
    fun `a cleared value means the plain flag, not an empty string`() {
        // "key=" is what the Console writes if an author types a value and deletes it. An empty
        // string would still pass `flags["k"] != null` while failing `== "true"` — a flag that
        // reads as set and as unset at the same time.
        assertEquals("true", OnboardingCTAFlag.parse("wants_callback=")?.value)
    }

    @Test
    fun `surrounding whitespace is trimmed from both halves`() {
        val flag = OnboardingCTAFlag.parse("  upsell_choice = booking  ")
        assertEquals("upsell_choice", flag?.key)
        assertEquals("booking", flag?.value)
    }

    @Test
    fun `no key means no flag`() {
        // The CTA still advances; it just records nothing. Returning a blank-keyed flag would write
        // an empty string into the bucket a host routes on.
        assertNull(OnboardingCTAFlag.parse(null))
        assertNull(OnboardingCTAFlag.parse(""))
        assertNull(OnboardingCTAFlag.parse("   "))
        assertNull(OnboardingCTAFlag.parse("=booking"))
    }

    // MARK: - the wire pair (the half that had already shipped broken for `permission`)

    @Test
    fun `encode omits the separator when there is no value`() {
        assertEquals("flag", OnboardingActionPair.encode("flag", null))
        assertEquals("flag", OnboardingActionPair.encode("flag", ""))
        assertEquals("flag", OnboardingActionPair.encode("flag", "   "))
    }

    @Test
    fun `encode carries the value`() {
        assertEquals("flag:upsell_choice=booking", OnboardingActionPair.encode("flag", "upsell_choice=booking"))
        assertEquals("permission:alarm", OnboardingActionPair.encode("permission", "alarm"))
    }

    @Test
    fun `decode splits on the first colon only`() {
        assertEquals("flag" to "utm=a:b", OnboardingActionPair.decode("flag:utm=a:b"))
        assertEquals("flag" to null, OnboardingActionPair.decode("flag"))
        assertEquals("social_login" to "google", OnboardingActionPair.decode("social_login:google"))
    }

    @Test
    fun `a flag survives the whole encode-decode-parse round trip`() {
        for (authored in listOf("wants_callback", "upsell_choice=booking", "utm=a=b", "wants_callback=")) {
            val wire = OnboardingActionPair.encode(OnboardingCTAFlag.ACTION_NAME, authored)
            val (action, value) = OnboardingActionPair.decode(wire)
            assertEquals(OnboardingCTAFlag.ACTION_NAME, action)
            assertEquals(
                "round trip changed the flag authored as '$authored'",
                OnboardingCTAFlag.parse(authored),
                OnboardingCTAFlag.parse(value),
            )
        }
    }

    // MARK: - the fold into the flat bucket

    @Test
    fun `merge collects flags under the well-known key without touching step answers`() {
        val before = mapOf<String, Any>("summary" to mapOf("upsell_choice" to "booking"))
        val after = OnboardingCTAFlag.merge(before, mapOf("upsell_choice" to "booking"))
        assertEquals(mapOf("upsell_choice" to "booking"), after[OnboardingCTAFlag.RESPONSES_KEY])
        assertEquals(before["summary"], after["summary"])
    }

    @Test
    fun `merge accumulates flags from several steps`() {
        var responses = OnboardingCTAFlag.merge(emptyMap(), mapOf("a" to "1"))
        responses = OnboardingCTAFlag.merge(responses, mapOf("b" to "2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), responses[OnboardingCTAFlag.RESPONSES_KEY])
    }

    @Test
    fun `no flags means no bucket at all`() {
        // An onboarding with no flag CTAs must not grow an empty `flags` key that a host would then
        // have to distinguish from "flags I did set".
        assertEquals(emptyMap<String, Any>(), OnboardingCTAFlag.merge(emptyMap(), emptyMap()))
    }

    // MARK: - the derived stat key (#595)

    @Test
    fun `an authored stat field id wins`() {
        assertEquals("group_size", summaryStatFieldId("block_5", 2, mapOf("field_id" to "group_size")))
    }

    @Test
    fun `a stat with no field id derives one from its position`() {
        // The renderer and RequiredFieldGate MUST derive this identically; if they drift, the gate
        // blocks on a key nothing writes and the step cannot be advanced at all.
        assertEquals("block_5_stat_2", summaryStatFieldId("block_5", 2, mapOf("input" to "stepper")))
        assertEquals("block_5_stat_0", summaryStatFieldId("block_5", 0, mapOf("field_id" to "")))
    }
}
