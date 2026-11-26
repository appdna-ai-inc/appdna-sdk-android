package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.generated.AppDNALifecycleDelegate
import ai.appdna.sdk.paywalls.PaywallConfigParser
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A WRONGLY-TYPED FIELD IS NOTICED, NOT ABSORBED.
 *
 * Android parses published config out of `Map<String, Any?>` with `as?` casts, so a field of the
 * wrong type yields null and the parser carries on with a default. iOS knows — `Codable` throws
 * and `FailableDecodable` catches it with a coding path — which is why configuration-issue reporting came out
 * asymmetric: the platform whose parser is more forgiving reported less, and a reader would
 * reasonably conclude it had fewer problems.
 *
 * 🔴 THE TWO CONTROLS MATTER AS MUCH AS THE ASSERTION. "a mismatch is reported" is worth nothing
 * unless absent and explicitly-null stay SILENT — those are ordinary states, and a parser that
 * cried about every unset field would be muted within a week.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedReadTest {

    private class Recording : AppDNALifecycleDelegate {
        val issues = mutableListOf<String>()
        override fun onConfigurationIssue(surface: String, id: String?, detail: String) {
            issues += detail
        }
    }

    private lateinit var delegate: Recording

    @Before
    fun setUp() {
        ConfigurationIssueReporter.resetForTesting()
        delegate = Recording()
        AppDNA.lifecycleDelegateOrNull = delegate
    }

    @After
    fun tearDown() {
        AppDNA.lifecycleDelegateOrNull = null
        ConfigurationIssueReporter.resetForTesting()
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    // ── the readers, directly ────────────────────────────────────────────────────────────────

    @Test
    fun `a present-but-wrong-typed value is reported and read as null`() {
        val m = mapOf<String, Any?>("corner_radius" to "12")
        assertNull("a String under a number field must not be coerced", m.num("paywall", "p", "corner_radius"))
        drain()
        assertEquals(1, delegate.issues.size)
        assertTrue(
            "the report must name the key and what was found; got: ${delegate.issues}",
            delegate.issues.single().contains("p.corner_radius") &&
                delegate.issues.single().contains("expected a number") &&
                delegate.issues.single().contains("String"),
        )
    }

    @Test
    fun `an ABSENT key is silent`() {
        assertNull(emptyMap<String, Any?>().num("paywall", "p", "corner_radius"))
        drain()
        assertTrue("absent is an ordinary state and says nothing", delegate.issues.isEmpty())
    }

    @Test
    fun `an explicitly NULL key is silent`() {
        assertNull(mapOf<String, Any?>("corner_radius" to null).num("paywall", "p", "corner_radius"))
        drain()
        assertTrue("null is an ordinary state and says nothing", delegate.issues.isEmpty())
    }

    @Test
    fun `a correct value is returned and silent`() {
        assertEquals(12, mapOf<String, Any?>("corner_radius" to 12).num("paywall", "p", "corner_radius"))
        assertEquals("hi", mapOf<String, Any?>("title" to "hi").str("paywall", "p", "title"))
        assertEquals(true, mapOf<String, Any?>("dismiss" to true).bool("paywall", "p", "dismiss"))
        drain()
        assertTrue(delegate.issues.isEmpty())
    }

    @Test
    fun `a boolean is not a number`() {
        // 🔴 JSON tells them apart and so does the console, so `true` under a numeric field is an
        // authoring mistake worth hearing about rather than a 1.
        assertNull(mapOf<String, Any?>("corner_radius" to true).num("paywall", "p", "corner_radius"))
        drain()
        assertEquals(1, delegate.issues.size)
    }

    // ── through the REAL parser ──────────────────────────────────────────────────────────────

    @Test
    fun `the paywall parser reports a mis-typed section field, and still renders the rest`() {
        val map = mapOf(
            "id" to "pw_1",
            "name" to "Typed read",
            "sections" to listOf(
                mapOf(
                    "id" to "sec_header",
                    "type" to "header",
                    "data" to mapOf(
                        "title" to "Go Premium",
                        // the defect: a number where a string belongs, and a string where a
                        // number belongs — both authored, both previously dropped in silence.
                        "subtitle" to 42,
                        "corner_radius" to "12",
                    ),
                ),
            ),
        )
        val config = PaywallConfigParser.parseSinglePaywall("pw_1", map)
        drain()

        val section = config?.sections?.firstOrNull { it.type == "header" }
        assertEquals("the good field must still arrive", "Go Premium", section?.data?.title)
        assertNull("the mis-typed one is dropped, as before", section?.data?.subtitle)

        val joined = delegate.issues.joinToString(" | ")
        assertTrue("the subtitle must be reported; got: $joined", joined.contains("subtitle"))
        assertTrue("and the corner_radius; got: $joined", joined.contains("corner_radius"))
        assertTrue(
            "the report must name the section so an author can find it; got: $joined",
            joined.contains("sections[sec_header]"),
        )
    }

    @Test
    fun `a clean paywall reports nothing`() {
        // THE CONTROL for the parser case: a correct document must be silent, or the signal above
        // means only "a document was parsed".
        val map = mapOf(
            "id" to "pw_2",
            "name" to "Clean",
            "sections" to listOf(
                mapOf("id" to "sec_header", "type" to "header", "data" to mapOf("title" to "Go Premium", "subtitle" to "Really")),
            ),
        )
        PaywallConfigParser.parseSinglePaywall("pw_2", map)
        drain()
        assertTrue("a clean document must be silent; got: ${delegate.issues}", delegate.issues.isEmpty())
    }

    // ── keys with two legitimate types ───────────────────────────────────────────────────────

    @Test
    fun `a comparison table's header list under columns is not reported, and its headers arrive`() {
        // The shape a published comparison table carries; the icon-grid count reader used to call it a drop.
        val map = mapOf(
            "id" to "pw_3",
            "name" to "Compare",
            "layout" to mapOf(
                "type" to "stack",
                "sections" to listOf(
                    mapOf("id" to "sec_table", "type" to "comparison_table", "config" to mapOf("columns" to listOf("Free", "Premium"))),
                    mapOf("id" to "sec_grid", "type" to "icon_grid", "config" to mapOf("columns" to 3)),
                ),
            ),
        )
        val config = PaywallConfigParser.parseSinglePaywall("pw_3", map)
        drain()
        assertTrue("a header list is a legitimate `columns`; got: ${delegate.issues}", delegate.issues.isEmpty())
        val table = config?.sections?.firstOrNull { it.id == "sec_table" }?.data
        assertEquals(listOf("Free", "Premium"), table?.table_columns?.map { it.label })
        assertNull("a list is not a column count", table?.columns)
        assertEquals(3, config?.sections?.firstOrNull { it.id == "sec_grid" }?.data?.columns)
    }

    @Test
    fun `a columns value that is neither a number nor a list is still reported`() {
        // THE CONTROL: the shared-type reader must not have become a reader that accepts anything.
        val m = mapOf<String, Any?>("columns" to "three")
        assertNull(m.numSharedWithList("paywall", "p", "columns"))
        drain()
        assertEquals(1, delegate.issues.size)
        assertTrue(delegate.issues.single().contains("p.columns"))
    }

    @Test
    fun `a Lottie block's legacy numeric speed plays as its rate and is not reported`() {
        val step = mapOf(
            "id" to "s1",
            "type" to "custom",
            "content_blocks" to listOf(
                mapOf("id" to "b_lottie", "type" to "lottie", "lottie_url" to "https://example.com/a.json", "speed" to 1L),
                mapOf("id" to "b_fast", "type" to "lottie", "speed" to 2.5),
                mapOf("id" to "b_stars", "type" to "star_background", "speed" to "fast"),
            ),
        )
        val blocks = ai.appdna.sdk.onboarding.OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks.orEmpty()
        drain()
        assertTrue("a numeric `speed` is a Lottie rate, not a dropped token; got: ${delegate.issues}", delegate.issues.isEmpty())
        assertEquals(1.0f, blocks.first { it.id == "b_lottie" }.lottie_speed)
        assertEquals(2.5f, blocks.first { it.id == "b_fast" }.lottie_speed)
        assertNull(blocks.first { it.id == "b_fast" }.speed)
        assertEquals("fast", blocks.first { it.id == "b_stars" }.speed)
        assertNull("a token is not a rate", blocks.first { it.id == "b_stars" }.lottie_speed)
    }

    @Test
    fun `a speed that is neither a string nor a number is still reported`() {
        val m = mapOf<String, Any?>("speed" to mapOf("x" to 1))
        assertNull(m.strSharedWithNum("onboarding_flow", "b", "speed"))
        drain()
        assertEquals(1, delegate.issues.size)
        assertTrue(delegate.issues.single().contains("b.speed"))
    }
}
