package ai.appdna.sdk

import ai.appdna.sdk.core.SurfaceMeasure
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyConfig
import ai.appdna.sdk.feedback.SurveyScreen
import ai.appdna.sdk.messages.InAppMessageView
import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.onboarding.ThreeZoneBlockLayout
import ai.appdna.sdk.paywalls.PaywallConfig
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four presented surfaces on a phone, a foldable (inner display portrait and landscape)
 * and a tablet-sized window, rendered through the REAL renderers and measured, plus a resize from one
 * mode to the other with state that must survive it.
 *
 * The window is large (1400 × 1000dp, mdpi so a pixel is a dp) and each surface is drawn in a box of the
 * geometry under test, which is exactly what the surface's own measuring sees on a device of that size:
 * the layout reads its constraints, not the configuration. Every "it splits" has a "it does not" beside
 * it, and every "state survives" has a negative control in which the same surface is REBUILT on resize
 * and the state is lost — so a pass cannot come from a harness that cannot see the difference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1400dp-h1000dp-mdpi")
class SurfaceSplitRenderTest {

    @get:Rule val compose = createComposeRule()

    private data class Geo(val name: String, val w: Float, val h: Float)

    private val phone = Geo("phone", 412f, 915f)
    private val innerPortrait = Geo("foldable inner portrait", 673f, 840f)
    private val innerLandscape = Geo("foldable inner landscape", 840f, 673f)
    private val tablet = Geo("tablet", 1280f, 800f)

    private val size = mutableStateOf(phone)

    private val shown = mutableStateOf<Pair<Int, @Composable () -> Unit>?>(null)
    private var rebuildOnResize = false
    private var started = false

    /** Shows [content] in the box of the current geometry; a second call REPLACES it (fresh state). */
    private fun host(rebuildOnResize: Boolean = false, content: @Composable () -> Unit) {
        this.rebuildOnResize = rebuildOnResize
        shown.value = ((shown.value?.first ?: 0) + 1) to content
        if (!started) {
            started = true
            compose.setContent {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    val g = size.value
                    val (version, body) = shown.value ?: return@MaterialTheme
                    Box(Modifier.size(g.w.dp, g.h.dp).testTag("surface")) {
                        key(version) {
                            if (this@SurfaceSplitRenderTest.rebuildOnResize) key(g) { SurfaceMeasure { body() } }
                            else SurfaceMeasure { body() }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun resize(to: Geo) {
        size.value = to
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun bounds(text: String): DpRect = compose.onAllNodesWithText(text, substring = true).onFirst().getUnclippedBoundsInRoot()
    private fun leadBounds(): DpRect = compose.onNodeWithTag("appdna_split_lead").getBoundsInRoot()
    private fun hasLead(): Boolean = compose.onAllNodesWithTag("appdna_split_lead").fetchSemanticsNodes().isNotEmpty()

    private fun containerLeft(g: Geo, split: Boolean): Float =
        (g.w - (if (split) minOf(g.w, 1040f) else minOf(g.w, 560f))) / 2f

    // ── Onboarding ──────────────────────────────────────────────────────────────────────────────

    private val heading = "Pick what matters to you"
    private val first = "First line of the step"
    private val last = "Last line of the step"

    private fun heroStep(): List<ContentBlock> = listOf(
        ContentBlock(id = "hero", type = "image", image_url = "https://example.invalid/hero.png", height = 240.0),
        ContentBlock(id = "h", type = "heading", text = heading),
        ContentBlock(id = "cta", type = "button", text = "Continue", action = "next", zone = "bottom"),
    )

    private fun tallHeroStep(): List<ContentBlock> =
        listOf(
            ContentBlock(id = "hero", type = "image", image_url = "https://example.invalid/hero.png", height = 200.0),
            ContentBlock(id = "first", type = "heading", text = first),
        ) + (1..14).map { ContentBlock(id = "s$it", type = "spacer", spacer_height = 120.0) } +
            listOf(
                ContentBlock(id = "last", type = "heading", text = last),
                ContentBlock(id = "cta", type = "button", text = "Continue", action = "next", zone = "bottom"),
            )

    @Composable
    private fun Step(blocks: List<ContentBlock>) {
        ThreeZoneBlockLayout(
            blocks = blocks,
            onAction = {},
            toggleValues = mutableMapOf(),
            inputValues = mutableMapOf(),
            loc = null,
            currentStepIndex = 0,
            totalSteps = 1,
        )
    }

    private fun assertColumn(g: Geo, text: String) {
        val column = minOf(g.w, 560f)
        val left = (g.w - column) / 2f
        val inset = maxOf(24f, column * 0.08f)
        val b = bounds(text)
        assertTrue("${g.name}: '$text' starts inside the centred column (${b.left} ≥ ${left + inset})", b.left.value >= left + inset - 1f)
        assertTrue("${g.name}: '$text' ends inside the centred column (${b.right} ≤ ${left + column - inset})", b.right.value <= left + column - inset + 1f)
    }

    @Test
    fun `onboarding with a hero is a column on a phone and on the inner display in portrait`() {
        for (g in listOf(phone, innerPortrait)) {
            size.value = g
            host { Step(heroStep()) }
            assertTrue("${g.name}: no leading pane", !hasLead())
            assertColumn(g, heading)
            assertColumn(g, "Continue")
        }
    }

    @Test
    fun `onboarding with a hero splits on the inner display in landscape and on a tablet, the CTA under the trailing pane`() {
        for (g in listOf(innerLandscape, tablet)) {
            size.value = g
            host { Step(heroStep()) }
            val left = containerLeft(g, split = true)
            val pane = minOf(g.w, 1040f) / 2f
            val lead = leadBounds()
            assertEquals("${g.name}: leading pane starts at the container", left, lead.left.value, 1f)
            assertEquals("${g.name}: leading pane is half the container", pane, lead.width.value, 1f)
            val h = bounds(heading)
            assertTrue("${g.name}: heading is in the trailing pane (${h.left} ≥ ${left + pane})", h.left.value >= left + pane)
            val cta = bounds("Continue")
            assertTrue("${g.name}: CTA is in the trailing pane", cta.left.value >= left + pane && cta.right.value <= left + 2 * pane + 1f)
            assertTrue("${g.name}: CTA is on screen", cta.bottom.value <= g.h + 1f)
        }
    }

    @Test
    fun `onboarding without a hero stays a centred column even where a split is allowed`() {
        size.value = tablet
        host {
            Step(
                listOf(
                    ContentBlock(id = "h", type = "heading", text = heading),
                    ContentBlock(id = "img", type = "image", image_url = "https://example.invalid/x.png", height = 120.0),
                    ContentBlock(id = "cta", type = "button", text = "Continue", action = "next", zone = "bottom"),
                ),
            )
        }
        assertTrue("a step that opens with its heading does not split", !hasLead())
        assertColumn(tablet, heading)
    }

    private fun onboardingScrollSurvivesResize(rebuild: Boolean): Boolean {
        size.value = innerLandscape
        host(rebuildOnResize = rebuild) { Step(tallHeroStep()) }
        assertTrue("starts split", hasLead())
        compose.onNodeWithText(last).performScrollTo()
        compose.waitForIdle()
        assertTrue("control: scrolled, the first line is above the viewport (${bounds(first)}, last ${bounds(last)})", bounds(first).bottom.value < 0f)
        // Fold to the column, then back to the split.
        resize(innerPortrait)
        assertTrue("after the fold there is no leading pane", !hasLead())
        val keptInColumn = bounds(first).bottom.value < 0f
        resize(innerLandscape)
        val keptInSplit = bounds(first).bottom.value < 0f
        return keptInColumn && keptInSplit
    }

    @Test
    fun `an onboarding step keeps its scroll position across a fold and an unfold`() {
        assertTrue("the scroll survives split → column → split", onboardingScrollSurvivesResize(rebuild = false))
    }

    @Test
    fun `negative control - a step REBUILT on resize loses its scroll, so the test can see the loss`() {
        assertTrue("rebuilt, the scroll is lost", !onboardingScrollSurvivesResize(rebuild = true))
    }

    private fun flow() = ai.appdna.sdk.onboarding.OnboardingConfigParser.parseFlowForTest(
        "f",
        mapOf(
            "id" to "f", "name" to "f", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf("First screen", "Second screen").mapIndexed { i, title ->
                mapOf(
                    "id" to "s$i", "type" to "content",
                    "content_blocks" to listOf(
                        mapOf("id" to "hero$i", "type" to "image", "image_url" to "https://example.invalid/$i.png", "height" to 200),
                        mapOf("id" to "h$i", "type" to "heading", "text" to title),
                        mapOf("id" to "cta$i", "type" to "button", "text" to "Continue", "action" to "next", "zone" to "bottom"),
                    ),
                )
            },
        ),
    )

    private fun exists(text: String) =
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun flowStepSurvivesResize(rebuild: Boolean): Boolean {
        size.value = innerLandscape
        val f = flow()
        host(rebuildOnResize = rebuild) {
            ai.appdna.sdk.onboarding.OnboardingFlowHost(
                flow = f, viewModel = null, delegate = null,
                onStepViewed = { _, _ -> }, onStepCompleted = { _, _, _ -> }, onStepSkipped = { _, _ -> },
                onFlowCompleted = {}, onFlowDismissed = { _, _ -> },
            )
        }
        assertTrue("split on the first step", hasLead())
        compose.onNodeWithText("Continue").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue("control: advanced to the second step", exists("Second screen"))
        resize(innerPortrait)
        return exists("Second screen") && !exists("First screen")
    }

    @Test
    fun `the flow stays on its step across a fold`() {
        assertTrue(flowStepSurvivesResize(rebuild = false))
    }

    @Test
    fun `negative control - a flow REBUILT on resize starts over`() {
        assertTrue(!flowStepSurvivesResize(rebuild = true))
    }

    // ── Paywall ─────────────────────────────────────────────────────────────────────────────────

    private fun paywall(withPitch: Boolean = true): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_split",
        mapOf(
            "id" to "pw_split", "name" to "Split",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to (if (withPitch) listOf(
                mapOf("id" to "sec_header", "type" to "header", "data" to mapOf("title" to "A synthetic pitch title")),
                mapOf(
                    "id" to "sec_features", "type" to "features",
                    "data" to mapOf("features" to listOf("First benefit", "Second benefit")),
                ),
            ) else emptyList()) + listOf(
                mapOf(
                    "id" to "sec_plans", "type" to "plans",
                    "data" to mapOf(
                        "plans" to listOf(
                            mapOf("id" to "plan_a", "product_id" to "app.synthetic.a", "label" to "Plan Alpha", "price_display" to "\$1", "is_default" to true),
                            mapOf("id" to "plan_b", "product_id" to "app.synthetic.b", "label" to "Plan Beta", "price_display" to "\$2"),
                        ),
                    ),
                ),
            ),
            "cta" to mapOf("text" to "Start now"),
        ),
    ) ?: error("paywall did not parse")

    @Test
    fun `a paywall is a column on a phone and splits pitch and plans on a foldable in landscape`() {
        size.value = phone
        host { PaywallScreen(config = paywall(), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        assertTrue("phone: no leading pane", !hasLead())
        assertTrue("phone: plans fit the window", bounds("Plan Beta").right.value <= phone.w)

        size.value = innerLandscape
        host { PaywallScreen(config = paywall(), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        val pane = innerLandscape.w / 2f
        assertTrue("split: there is a leading pane", hasLead())
        assertTrue("split: the pitch is in the leading pane", bounds("A synthetic pitch title").right.value <= pane + 1f)
        assertTrue("split: the plans are in the trailing pane", bounds("Plan Beta").left.value >= pane)
        assertTrue("split: the first plan starts below the close control (48dp)", bounds("Plan Alpha").top.value >= 48f)
        val cta = bounds("Start now")
        assertTrue("split: the CTA is in the trailing pane, on screen ($cta)\n${compose.onRoot().printToString(40)}", cta.left.value >= pane && cta.bottom.value <= innerLandscape.h + 1f)
    }

    @Test
    fun `a paywall with no pitch stays a centred column on a tablet`() {
        size.value = tablet
        host { PaywallScreen(config = paywall(withPitch = false), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        assertTrue("no leading pane", !hasLead())
        val left = containerLeft(tablet, split = false)
        val b = bounds("Plan Beta")
        assertTrue("plans are inside the 560 column", b.left.value >= left && b.right.value <= left + 560f + 1f)
    }

    private fun paywallPlanSurvivesResize(rebuild: Boolean): String? {
        size.value = innerLandscape
        var bought: String? = null
        host(rebuildOnResize = rebuild) {
            PaywallScreen(config = paywall(), onPlanSelected = { p, _ -> bought = p.id }, onRestore = {}, onDismiss = {})
        }
        compose.onNodeWithText("Plan Beta").performClick()
        compose.waitForIdle()
        resize(innerPortrait)
        assertTrue("folded: no leading pane", !hasLead())
        compose.onNodeWithText("Start now", substring = true).performClick()
        compose.waitForIdle()
        return bought
    }

    @Test
    fun `the selected plan survives a fold, and the CTA buys it`() {
        assertEquals("plan_b", paywallPlanSurvivesResize(rebuild = false))
    }

    @Test
    fun `negative control - a paywall REBUILT on resize falls back to the default plan`() {
        assertNotEquals("plan_b", paywallPlanSurvivesResize(rebuild = true))
    }

    // ── In-app messages ─────────────────────────────────────────────────────────────────────────

    private fun message(type: String, image: Boolean = true) = MessageConfigParser.parseSingleMessage(
        mapOf(
            "name" to "synthetic",
            "message_type" to type,
            "content" to mapOf(
                "title" to "A synthetic message title",
                "body" to "Body copy",
                "cta_text" to "Primary action",
            ) + (if (image) mapOf("image_url" to "https://example.invalid/hero.png") else emptyMap()),
            "trigger_rules" to mapOf("event" to "e"),
        ),
    ) ?: error("message did not parse")

    @Test
    fun `a full-screen message splits hero and copy on a tablet and is a column on a phone`() {
        size.value = tablet
        host { InAppMessageView(config = message("fullscreen"), onCTATap = {}, onDismiss = {}) }
        val left = containerLeft(tablet, split = true)
        assertTrue("split: there is a leading pane", hasLead())
        assertTrue("split: title in the trailing pane", bounds("A synthetic message title").left.value >= left + 520f)
        assertTrue("split: CTA in the trailing pane", bounds("Primary action").left.value >= left + 520f)

        size.value = phone
        host { InAppMessageView(config = message("fullscreen"), onCTATap = {}, onDismiss = {}) }
        assertTrue("phone: no leading pane", !hasLead())
    }

    @Test
    fun `a full-screen message without a hero, and a modal, stay single and at most 560 wide`() {
        size.value = tablet
        host { InAppMessageView(config = message("fullscreen", image = false), onCTATap = {}, onDismiss = {}) }
        assertTrue("no hero: no leading pane", !hasLead())
        val col = containerLeft(tablet, split = false)
        val t = bounds("A synthetic message title")
        assertTrue("no hero: copy inside the centred column", t.left.value >= col && t.right.value <= col + 560f + 1f)

        host { InAppMessageView(config = message("modal"), onCTATap = {}, onDismiss = {}) }
        assertTrue("modal: no leading pane", !hasLead())
        val cta = bounds("Primary action")
        assertTrue("modal: the card is at most 560 wide (CTA ${cta.width})", cta.width.value <= 560f)
        assertEquals("modal: the card is centred", tablet.w / 2f, (cta.left.value + cta.right.value) / 2f, 2f)
    }

    // ── Surveys ─────────────────────────────────────────────────────────────────────────────────

    private fun survey(): SurveyConfig = SurveyConfig.fromMap(
        mapOf(
            "name" to "Probe", "survey_type" to "nps",
            "questions" to listOf(
                mapOf("id" to "q1", "type" to "nps", "text" to "How likely are you to recommend us?", "required" to true),
            ),
            "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
            "appearance" to mapOf("presentation" to "fullscreen", "dismiss_allowed" to true, "show_progress" to false),
        ),
    )!!

    @Test
    fun `a survey stays one centred column at most 560 wide on a tablet, full width on a phone`() {
        size.value = tablet
        host { SurveyScreen(config = survey(), onComplete = {}, onDismiss = {}, isDark = false) }
        val c = compose.onNodeWithTag("appdna_survey_column").getBoundsInRoot()
        assertTrue("tablet: column ≤ 560 (${c.width})", c.width.value <= 560f + 1f)
        assertEquals("tablet: column centred", tablet.w / 2f, (c.left.value + c.right.value) / 2f, 1f)

        size.value = phone
        host { SurveyScreen(config = survey(), onComplete = {}, onDismiss = {}, isDark = false) }
        val p = compose.onNodeWithTag("appdna_survey_column").getBoundsInRoot()
        assertEquals("phone: the column is the window less the survey padding", phone.w - 48f, p.width.value, 1f)
    }

    private fun surveyScoreSurvivesResize(rebuild: Boolean): List<SurveyAnswer>? {
        size.value = innerPortrait
        var completed: List<SurveyAnswer>? = null
        host(rebuildOnResize = rebuild) {
            SurveyScreen(config = survey(), onComplete = { completed = it }, onDismiss = {}, isDark = false)
        }
        compose.onNodeWithText("7").performClick()
        compose.waitForIdle()
        resize(innerLandscape)
        resize(innerPortrait)
        compose.onNodeWithText("Submit").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        return completed
    }

    @Test
    fun `a survey score survives an unfold and a fold, and is submitted`() {
        val answers = surveyScoreSurvivesResize(rebuild = false)
        assertTrue("completed with the score: $answers", answers?.any { it.questionId == "q1" && "${it.answer}".startsWith("7") } == true)
    }

    @Test
    fun `negative control - a survey REBUILT on resize loses the score`() {
        val answers = surveyScoreSurvivesResize(rebuild = true)
        assertTrue("rebuilt: the score is gone ($answers)", answers?.any { it.questionId == "q1" } != true)
    }

    @Suppress("unused")
    private fun MutableState<Geo>.describe() = value.name
}
