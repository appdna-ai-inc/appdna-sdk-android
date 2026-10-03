package ai.appdna.sdk.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Compose-rule proofs the fixture drivers CANNOT give (they recompose by
 * hand and never render a view):
 *
 *  1. a `refresh_step` button nested in a row, a stack, a section_background, a multi_buttons group and
 *     on a carousel page fires ONE interaction each through the step's wiring, and one inside an option
 *     sheet fires NONE;
 *  2. the coordinator's state is OBSERVABLE: a tap shows the indicator, a nil reply hides it and the
 *     button is clickable again — with no other state change forcing the recomposition;
 *  3. same composition, new presentation (0→1→0 inside the exit tween): `remember(myPresentation)`
 *     builds a new coordinator, so an OTP fire on the returned step is accepted;
 *  4. end to end through the REAL flow host: a reply that carries ONLY `dataContext` recomposes the
 *     rendered option list (the layer is snapshot state).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RefreshStepWiringComposeTest {

    @get:Rule
    val compose = createComposeRule()

    /*
     * The refresh spinner is an INFINITE animation: with the test clock auto-advancing, `waitForIdle`
     * never returns while one is drawn. Every test here therefore drives the clock by hand
     * (`autoAdvance = false`) — frames are advanced explicitly, and nothing else can recompose the UI.
     */
    private fun frames(n: Int = 3) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    private fun settleUntil(what: String, maxFrames: Int = 300, cond: () -> Boolean) {
        repeat(maxFrames) {
            if (cond()) return
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue("never reached: $what", cond())
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The Select picks write the process-wide store; leave nothing behind for other suites. */
    @org.junit.After
    fun resetSelectedOptions() = SelectedOptionStore.resetForTesting()

    private fun blocksOf(vararg blocks: Map<String, Any>): List<ContentBlock> =
        OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "content", "config" to mapOf("content_blocks" to blocks.toList())),
        )!!.config.content_blocks!!.toList()

    private fun refresh(id: String, label: String, value: String) =
        mapOf("id" to id, "type" to "button", "text" to label, "action" to "refresh_step", "action_value" to value)

    /** A coordinator whose host answers each call from [replies] (one deferred per call). */
    private class Host {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val replies = mutableListOf<CompletableDeferred<ElementInteractionResult?>>()
        val call: ElementInteractionCall = { _, blockId, action, value, _ ->
            calls += Triple(blockId, action, value)
            val d = CompletableDeferred<ElementInteractionResult?>()
            replies += d
            d.await()
        }
    }

    private fun renderWithStepInteraction(blocks: List<ContentBlock>, host: Host, pending: () -> Boolean = { false }) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val coordinator = androidx.compose.runtime.remember {
                InteractionCoordinator(
                    stepId = "s", scope = scope, isCurrentPresentation = { true }, drawSeq = { 1 },
                    hasHandler = { true }, call = host.call,
                )
            }
            val onInteract: (String, String, String?) -> Unit = { id, action, value ->
                coordinator.start(id, action, value, emptyMap(), pending()) { _, _ -> }
            }
            CompositionLocalProvider(LocalStepInteraction provides StepInteraction(onInteract, coordinator, pending)) {
                ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
            }
        }
        compose.mainClock.autoAdvance = false
        frames()
    }

    @Test
    fun `refresh_step buttons nested in every container fire one interaction each`() {
        val host = Host()
        renderWithStepInteraction(
            blocksOf(
                mapOf("id" to "mb", "type" to "multi_buttons", "stack_children" to listOf(refresh("mb_more", "More (group)", "mb"))),
                mapOf("id" to "row_1", "type" to "row", "stack_children" to listOf(refresh("row_more", "More (row)", "row"))),
                mapOf("id" to "stack_1", "type" to "stack", "stack_children" to listOf(refresh("stack_more", "More (stack)", "stack"))),
                mapOf("id" to "sec_1", "type" to "section_background", "stack_children" to listOf(refresh("section_more", "More (section)", "section"))),
                mapOf("id" to "car_1", "type" to "carousel", "stack_children" to listOf(refresh("carousel_more", "More (carousel page)", "carousel"))),
            ),
            host,
        )
        val labels = listOf("More (group)", "More (row)", "More (stack)", "More (section)", "More (carousel page)")
        for ((i, label) in labels.withIndex()) {
            compose.onNodeWithText(label).performClick()
            settleUntil("call for $label") { host.calls.size == i + 1 }
            // Without the reply the next tap would be refused by the in-flight lock.
            host.replies[i].complete(null)
            frames()
        }
        assertEquals(
            listOf("mb_more", "row_more", "stack_more", "section_more", "carousel_more"),
            host.calls.map { it.first },
        )
        assertEquals(listOf("mb", "row", "stack", "section", "carousel"), host.calls.map { it.third })
        assertTrue(host.calls.all { it.second == "refresh" })
    }

    @Test
    fun `a refresh_step button inside an option sheet fires nothing`() {
        val host = Host()
        renderWithStepInteraction(
            blocksOf(
                mapOf(
                    "id" to "sel", "type" to "input_select", "field_id" to "pick",
                    "field_config" to mapOf("display_style" to "stacked"),
                    "field_options" to listOf(
                        mapOf(
                            "id" to "o1", "label" to "Open the sheet", "value" to "o1",
                            "sheet_blocks" to listOf(refresh("sheet_more", "More (sheet)", "sheet")),
                        ),
                    ),
                ),
            ),
            host,
        )
        compose.onNodeWithText("Open the sheet").performClick()
        settleUntil("the option sheet is shown") { exists("More (sheet)") }
        compose.onNodeWithText("More (sheet)").performClick()
        frames(10)
        assertEquals(0, host.calls.size)
    }

    @Test
    fun `tap shows the indicator, a nil reply hides it and the button is clickable again`() {
        val host = Host()
        renderWithStepInteraction(
            blocksOf(refresh("show_more", "Show more", "more"), refresh("show_more_2", "Show more 2", "more2")),
            host,
        )
        compose.onNodeWithTag(REFRESH_SPINNER_TAG).assertDoesNotExist()
        compose.onNodeWithText("Show more").performClick()
        settleUntil("the call started") { host.calls.size == 1 }
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertExists()
        // Every refresh button is locked while one call is in flight.
        compose.onNode(hasText("Show more 2")).assertIsNotEnabled()
        // Nothing but the reply changes: no recomposition is forced from outside.
        host.replies.single().complete(null)
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Show more 2")).assertIsEnabled()
        compose.onNode(hasText("Show more")).assertIsEnabled()
        // …and it really is clickable again: a second tap is a second call.
        compose.onNodeWithText("Show more").performClick()
        settleUntil("the second call started") { host.calls.size == 2 }
        // End with nothing in flight: no infinite spinner may outlive the test.
        host.replies[1].complete(null)
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a refresh button is not clickable while the step is pending`() {
        val host = Host()
        renderWithStepInteraction(blocksOf(refresh("show_more", "Show more", "more")), host, pending = { true })
        compose.onNode(hasText("Show more")).assertIsNotEnabled()
        compose.onNodeWithText("Show more").performClick()
        frames(10)
        assertEquals(0, host.calls.size)
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `same composition, new presentation — the returned step's OTP fire is accepted`() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "a", "type" to "content", "config" to mapOf("content_blocks" to listOf(mapOf("id" to "otp", "type" to "otp_input")))),
        )!!
        val other = OnboardingConfigParser.parseStepForTest(mapOf("id" to "b", "type" to "content", "config" to emptyMap<String, Any>()))!!
        val presentations = HostDataPresentations()
        val access = HostDataInteractionAccess(mutableMapOf(), HostDataInteractionStore(), presentations)
        val host = Host()
        val p = presentations.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = false)
        var wiring by mutableStateOf(StepHostDataWiring(access, p, host.call) { true })
        var coordinator: InteractionCoordinator? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            coordinator = rememberInteractionCoordinator("a", wiring, scope) { _, _, _, _ -> null }
        }
        compose.waitForIdle()
        val first = coordinator!!
        assertNotNull(first.start("otp", "otp_entered", "1", emptyMap(), false) { _, _ -> })
        compose.waitForIdle()
        // 0 → 1 → 0 inside the exit tween: the same composition, a NEW presentation.
        presentations.presentationFor(other, 1, hasDelegate = true, hasCachedOverride = false)
        val p2 = presentations.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = false)
        assertNotSame(p, p2)
        wiring = StepHostDataWiring(access, p2, host.call) { true }
        compose.waitForIdle()
        val second = coordinator!!
        assertNotSame("remember(myPresentation) builds a new coordinator", first, second)
        // The old instance refuses (not current); the new one accepts although the old call is in flight.
        assertNull(first.start("otp", "otp_entered", "2", emptyMap(), false) { _, _ -> })
        assertNotNull(second.start("otp", "otp_entered", "3", emptyMap(), false) { _, _ -> })
        compose.waitForIdle()
        assertEquals(listOf("1", "3"), host.calls.map { it.third })
        host.replies.forEach { it.complete(null) }
        compose.waitForIdle()
    }

    @Test
    fun `a dataContext-only reply recomposes the rendered option list through the real flow host`() {
        fun rec(id: String) = mapOf("id" to id, "name" to id.uppercase())
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "step_eir", "type" to "content",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "sel", "type" to "input_select", "field_id" to "select_recs",
                            "field_options" to listOf(
                                mapOf(
                                    "id" to "opt_1", "label" to "Recommended", "value" to "recommended",
                                    "data_templates" to mapOf("label" to "{{item.name}}", "value" to "{{item.id}}"),
                                ),
                            ),
                            "field_config" to mapOf(
                                "display_style" to "stacked",
                                "repeat" to mapOf("source" to "hook_data.recommendations", "template_option_id" to "opt_1", "max" to 20),
                            ),
                        ),
                        refresh("show_more", "Show more options", "more"),
                        // A NESTED refresh button: reaches the host only through BlockBasedStepView's
                        // LocalStepInteraction provider (RowBlock passes no onInteract).
                        mapOf("id" to "row_1", "type" to "row", "stack_children" to listOf(refresh("row_more", "More (row)", "row"))),
                    ),
                ),
            ),
        )!!
        val interactionReply = CompletableDeferred<ElementInteractionResult?>()
        val calls = mutableListOf<Pair<String, String?>>()
        val delegate = object : AppDNAOnboardingDelegate {
            override suspend fun onBeforeStepRender(
                flowId: String, stepId: String, stepIndex: Int, stepType: String, responses: Map<String, Any>,
            ): StepConfigOverride = StepConfigOverride(dataContext = mapOf("recommendations" to listOf("a", "b", "c", "d").map { rec(it) }))

            override suspend fun onElementInteraction(
                flowId: String, stepId: String, blockId: String, action: String, value: String?, inputValues: Map<String, Any>,
            ): ElementInteractionResult? {
                calls += "$stepId/$blockId/$action" to value
                return interactionReply.await()
            }
        }
        val flow = OnboardingFlowConfig(
            id = "f", name = "f", version = 1, steps = listOf(step).toImmutableList(), settings = OnboardingSettings(),
        )
        compose.setContent {
            OnboardingFlowHost(
                flow = flow, viewModel = null, delegate = delegate,
                onStepViewed = { _, _ -> }, onStepCompleted = { _, _, _ -> }, onStepSkipped = { _, _ -> },
                onFlowCompleted = {}, onFlowDismissed = { _, _ -> },
            )
        }
        compose.mainClock.autoAdvance = false
        settleUntil("page 1 rendered") { exists("D") }
        compose.onNodeWithText("Show more options").performClick()
        settleUntil("the host was called") { calls.isNotEmpty() }
        assertEquals(listOf("step_eir/show_more/refresh" to "more"), calls)
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertExists()
        // A reply with ONLY dataContext — no fieldOptions, no patches, no input values.
        interactionReply.complete(ElementInteractionResult(dataContext = mapOf("recommendations" to listOf("e", "f", "g", "h").map { rec(it) })))
        settleUntil("page 2 rendered from the dataContext-only reply") { exists("H") }
        compose.onNodeWithText("E").assertExists()
        compose.onNodeWithText("A").assertDoesNotExist()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
        // The nested row button, through the same real flow host and step provider.
        compose.onNodeWithText("More (row)").performClick()
        settleUntil("the nested row button reached the host") { calls.size == 2 }
        assertEquals("step_eir/row_more/refresh" to "row", calls[1])
        frames()
    }

    private fun standaloneStep() = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "solo", "type" to "content",
            "config" to mapOf("content_blocks" to listOf(refresh("show_more", "Show more", "more"))),
        ),
    )!!

    @Test
    fun `a step shown without the flow host and without an interaction seam makes no refresh call (C5_2)`() {
        val step = standaloneStep()
        compose.setContent {
            OnboardingStepView(step = step, effectiveConfig = step.config, onNext = {}, onSkip = {})
        }
        compose.mainClock.autoAdvance = false
        frames()
        compose.onNodeWithText("Show more").performClick()
        frames(10)
        // No handler: no call, so no loading state — the button stays as it was.
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Show more")).assertIsEnabled()
    }

    @Test
    fun `a step shown without the flow host but WITH an interaction seam still refreshes`() {
        val step = standaloneStep()
        val calls = mutableListOf<String>()
        val reply = CompletableDeferred<AppliedInteraction?>()
        compose.setContent {
            OnboardingStepView(
                step = step, effectiveConfig = step.config, onNext = {}, onSkip = {},
                performInteraction = { blockId, action, _, _ -> calls += "$blockId/$action"; reply.await() },
            )
        }
        compose.mainClock.autoAdvance = false
        frames()
        compose.onNodeWithText("Show more").performClick()
        settleUntil("the legacy seam was called") { calls.isNotEmpty() }
        assertEquals(listOf("show_more/refresh"), calls)
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertExists()
        reply.complete(null)
        frames()
        compose.onNodeWithTag(REFRESH_SPINNER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }


    @Test
    fun `without the flow host, the default seam means no handler — a refresh start is refused (C5_2)`() {
        var noSeam: InteractionCoordinator? = null
        var withSeam: InteractionCoordinator? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            // Exactly what BlockBasedStepView builds when OnboardingStepView is used on its own.
            noSeam = rememberInteractionCoordinator("solo", null, scope, NoElementInteraction)
            withSeam = rememberInteractionCoordinator("solo2", null, scope) { _, _, _, _ -> null }
        }
        compose.waitForIdle()
        // The default seam: refused — no call, no loading state (and the C5.2 debug log).
        assertNull(noSeam!!.start("show_more", "refresh", "more", emptyMap(), false) { _, _ -> })
        assertNull(noSeam!!.loadingBlockId)
        assertEquals(false, noSeam!!.inFlight)
        // A caller-supplied seam is a handler.
        assertNotNull(withSeam!!.start("show_more", "refresh", "more", emptyMap(), false) { _, _ -> })
        compose.waitForIdle()
    }

}
