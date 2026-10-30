package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.TemplateContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android host-data regressions, including the cross-platform
 * ones also found on iOS. Compose cases render the SHIPPING renderer and tap it; the rest
 * drive the same non-Compose seams the step view uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class HostDataAuditRegressionTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun reset() {
        SelectedOptionStore.resetForTesting()
    }

    private fun opt(v: String, label: String = v.uppercase()) = InputOption(value = v, label = label, id = v)

    private fun block(
        id: String,
        type: String,
        options: List<InputOption>? = null,
        fieldConfig: Map<String, Any>? = null,
        fieldLabel: String? = null,
        children: List<ContentBlock>? = null,
        placeholder: String? = null,
    ) = ContentBlock(
        id = id,
        type = type,
        field_id = id,
        field_label = fieldLabel,
        field_placeholder = placeholder,
        field_options = options?.toImmutableList(),
        field_config = fieldConfig,
        children = children?.toImmutableList(),
    )

    private fun step(vararg blocks: Map<String, Any?>, id: String = "s1"): OnboardingStep =
        OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to id, "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
                "config" to mapOf("content_blocks" to blocks.toList()),
            ),
        ) ?: error("step did not parse")

    // ── M1 — chips never write a pruned value back ──────────────────────────────────────────────

    @Test
    fun `M1 chips - a value pruned after host data settles is not written back on the next tap`() {
        val inputValues = mutableStateMapOf<String, Any>("drink" to listOf("a", "b"))
        val before = block("drink", "input_chips", listOf(opt("a"), opt("b"), opt("c")))
        val shown = mutableStateOf(listOf(before))
        compose.setContent {
            ContentBlockRendererView(blocks = shown.value, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        // Host data settles: `b` is no longer rendered → the step's prune removes it.
        val after = block("drink", "input_chips", listOf(opt("a"), opt("c")), mapOf("resolve_state" to "scoped"))
        compose.runOnIdle {
            shown.value = listOf(after)
            assertTrue(pruneHostDataSelections(listOf(after), setOf("drink"), inputValues))
        }
        compose.waitForIdle()
        compose.onNodeWithText("C").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a", "c"), inputValues["drink"])
    }

    @Test
    fun `M1 multi select - toggles against the pruned value, not the view copy`() {
        val inputValues = mutableStateMapOf<String, Any>("drink" to listOf("a", "b"))
        val cfg = mapOf<String, Any>("display_style" to "stacked", "multi_select" to true)
        val shown = mutableStateOf(listOf(block("drink", "input_select", listOf(opt("a"), opt("b"), opt("c")), cfg)))
        compose.setContent {
            ContentBlockRendererView(blocks = shown.value, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        val after = block("drink", "input_select", listOf(opt("a"), opt("c")), cfg + ("resolve_state" to "scoped"))
        compose.runOnIdle {
            shown.value = listOf(after)
            pruneHostDataSelections(listOf(after), setOf("drink"), inputValues)
        }
        compose.waitForIdle()
        compose.onNodeWithText("C").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a", "c"), inputValues["drink"])
    }

    @Test
    fun `M1 chip helpers`() {
        assertEquals(setOf("a", "b"), chipSelectionOf(listOf("a", "b")))
        assertEquals(setOf("a"), chipSelectionOf("a"))
        assertEquals(emptySet<String>(), chipSelectionOf(null))
        assertEquals(setOf("a", "c"), toggleChipSelection(setOf("a"), "c", null))
        assertEquals(setOf("a"), toggleChipSelection(setOf("a", "c"), "c", 1))
        assertEquals(setOf("a"), toggleChipSelection(setOf("a"), "c", 1))
    }

    // ── m2 — segmented re-syncs with the step's value ───────────────────────────────────────────

    @Test
    fun `m2 segmented sync - stored value wins, a pruned value clears, the visual default survives`() {
        assertEquals("y", segmentedSync(shown = "x", previous = null, next = "y"))
        assertNull(segmentedSync(shown = "y", previous = "x", next = "y"))
        // Pruned from outside: it was stored, now it is gone.
        assertEquals("", segmentedSync(shown = "y", previous = "y", next = null))
        // First frame: nothing stored yet — the first-option default is kept.
        assertNull(segmentedSync(shown = "x", previous = null, next = null))
    }

    // ── m1 — the dropdown label is derived, not remembered ──────────────────────────────────────

    @Test
    fun `m1 dropdown - a restored value shows its label once its options arrive, and the placeholder after a prune`() {
        val inputValues = mutableStateMapOf<String, Any>("city" to "p")
        val shown = mutableStateOf(listOf(block("city", "input_select", emptyList(), placeholder = "Pick a city")))
        compose.setContent {
            ContentBlockRendererView(blocks = shown.value, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.onNodeWithText("Pick a city").assertExists()
        compose.runOnIdle { shown.value = listOf(block("city", "input_select", listOf(opt("p", "Paris")), placeholder = "Pick a city")) }
        compose.onNodeWithText("Paris").assertExists()
    }

    // ── m3 — nested children honour empty_state hidden ──────────────────────────────────────────

    @Test
    fun `m3 a hidden select nested in a row renders nothing`() {
        val hidden = block("sel", "input_select", emptyList(), mapOf("empty_state" to mapOf("mode" to "hidden")), fieldLabel = "Hidden select")
        val shownSel = block("sel2", "input_select", listOf(opt("a")), fieldLabel = "Shown select")
        val row = ContentBlock(id = "row", type = "row", children = persistentListOf(hidden, shownSel))
        compose.setContent {
            CompositionLocalProvider(
                LocalHostDataRawResolved provides true,
                LocalHostDataRawResolvedIds provides setOf("row", "sel", "sel2"),
            ) {
                ContentBlockRendererView(blocks = listOf(row), onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
            }
        }
        compose.onNodeWithText("Shown select").assertExists()
        compose.onNodeWithText("Hidden select").assertDoesNotExist()
    }

    // ── m4 — the outgoing step keeps its pending flag ───────────────────────────────────────────

    @Test
    fun `m4 the outgoing step of a transition stays pending and the incoming one has its own flag`() {
        val a = step(
            mapOf("id" to "sel", "type" to "input_select", "field_id" to "sel",
                "field_options" to listOf(mapOf("id" to "o1", "value" to "o1", "label" to "{{hook_data.name}}"))),
            id = "a",
        )
        val b = step(mapOf("id" to "t", "type" to "text", "text" to "plain"), id = "b")
        val presentations = HostDataPresentations()
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false)
        assertTrue(pa.pending)
        presentations.presentationFor(b, 1, hasDelegate = true, hasCachedOverride = false)
        // Back from A to B while A's call is in flight: A (outgoing) keeps pending → it is not
        // re-resolved "after pending" with no hook data, and so prunes nothing.
        assertTrue(presentations.pendingFor(0, "a"))
        assertFalse(presentations.pendingFor(1, "b"))
        assertFalse(presentations.pendingFor(2, "c"))

        // And a pending re-resolve prunes nothing: the marker is `pending`, not `scoped`.
        val pipeline = HostDataStepPipeline(a, legacyContext = { null })
        val r = pipeline.resolve(HostDataInputs(pending = true, stepInputs = mapOf("sel" to "o1")))
        val values = mutableMapOf<String, Any>("sel" to "o1")
        assertFalse(pruneHostDataSelections(r.blocks, r.rawResolvedIds, values))
        assertEquals("o1", values["sel"])
    }

    // ── m5 — memo key covers every root, per block; buildContext only on a miss ─────────────────

    @Test
    fun `m5 a TemplateEngine-root value change re-resolves, per block, without rebuilding the context on a hit`() {
        var greeting = "Hello"
        val tctx = TemplateContext(
            userTraits = null,
            remoteConfig = { if (it == "greeting") greeting else null },
            onboardingResponses = emptyMap(), computedData = emptyMap(), sessionData = emptyMap(), deviceInfo = emptyMap(),
        )
        val s = step(
            mapOf("id" to "legacy", "type" to "text", "text" to "{{remote_config.greeting}}"),
            mapOf("id" to "host", "type" to "text", "text" to "Hi {{hook_data.name}}"),
        )
        val p = HostDataStepPipeline(s, legacyContext = { tctx })
        val inputs = HostDataInputs(hookData = mapOf("name" to "Ann"))
        val r1 = p.resolve(inputs)
        assertEquals("Hello", r1.blocks[0].text)
        assertEquals("Hi Ann", r1.blocks[1].text)
        assertEquals(2, p.rawPassRuns)

        // Nothing changed: both blocks hit.
        p.resolve(inputs)
        assertEquals(2, p.rawPassRuns)

        // Only the hook data changed: only the block that reads it re-resolves.
        val r2 = p.resolve(inputs.copy(hookData = mapOf("name" to "Bo")))
        assertEquals("Hi Bo", r2.blocks[1].text)
        assertEquals(3, p.rawPassRuns)

        // A remote-config value changed: the block reading it re-resolves (the old key missed this).
        greeting = "Bonjour"
        val r3 = p.resolve(inputs.copy(hookData = mapOf("name" to "Bo")))
        assertEquals("Bonjour", r3.blocks[0].text)
        assertEquals(4, p.rawPassRuns)
    }

    @Test
    fun `m5 a step with no TemplateEngine token does not rebuild the context on a memo hit`() {
        val s = step(mapOf("id" to "host", "type" to "text", "text" to "Hi {{hook_data.name}}"))
        val p = HostDataStepPipeline(s, legacyContext = { null })
        val inputs = HostDataInputs(hookData = mapOf("name" to "Ann"))
        p.resolve(inputs)
        val builds = p.legacyContextBuilds
        repeat(3) { p.resolve(inputs) }
        assertEquals(builds, p.legacyContextBuilds)
    }

    // ── m6 — host options never pick up an authored raw sheet ───────────────────────────────────

    @Test
    fun `m6 a host option reusing an authored id does not show the authored sheet`() {
        val s = step(
            mapOf(
                "id" to "sel", "type" to "input_select", "field_id" to "sel",
                "field_options" to listOf(
                    mapOf("id" to "o1", "value" to "o1", "label" to "Authored",
                        "sheet_blocks" to listOf(mapOf("id" to "sheet_t", "type" to "text", "text" to "Authored sheet {{hook_data.x}}"))),
                ),
            ),
        )
        val p = HostDataStepPipeline(s, legacyContext = { null })
        assertTrue(p.resolve(HostDataInputs()).sheetRaw.containsKey("sel|o1"))
        val hostOption = InputOption(value = "o1", label = "Host", id = "o1")
        assertFalse(p.resolve(HostDataInputs(fieldOptionsOverrides = mapOf("sel" to listOf(hostOption)))).sheetRaw.containsKey("sel|o1"))
        val override = StepConfigOverride(fieldOptions = mapOf("sel" to listOf(hostOption)))
        assertFalse(p.resolve(HostDataInputs(stepOverride = override)).sheetRaw.containsKey("sel|o1"))
    }

    // ── m7 — interaction patches go through the structural walker ───────────────────────────────

    @Test
    fun `m7 a field_config patch gets the URL rule and resolves strings`() {
        val s = step(
            mapOf("id" to "sel", "type" to "input_select", "field_id" to "sel",
                "field_options" to listOf(mapOf("id" to "o1", "value" to "o1", "label" to "A")),
                "field_config" to mapOf("background_image_url" to "https://cdn.example.com/authored.png")),
        )
        val p = HostDataStepPipeline(s, legacyContext = { null })
        val r = p.resolve(
            HostDataInputs(
                hookData = mapOf("name" to "Ann"),
                fieldConfigOverrides = mapOf(
                    "sel" to mapOf(
                        "background_image_url" to "{{hook_data.missing}}",
                        "icon_url" to "https://cdn.example.com/{{hook_data.name}}.png",
                        "empty_text" to "Hi {{hook_data.name}}",
                        "resolve_state" to "scoped",
                    ),
                ),
            ),
        )
        val fc = r.blocks.single().field_config!!
        // An unresolved URL is dropped from the patch, so the authored value stays.
        assertEquals("https://cdn.example.com/authored.png", fc["background_image_url"])
        assertEquals("https://cdn.example.com/Ann.png", fc["icon_url"])
        assertEquals("Hi Ann", fc["empty_text"])
        // Markers cannot be patched.
        assertFalse(fc.containsKey("resolve_state"))
    }

    // ── m8 — the production re-fire gate ────────────────────────────────────────────────────────

    @Test
    fun `m8 the step effect fires on a new index, not on recreation, and once more after a cancelled call`() = runTest {
        val p = HostDataPresentation("a", 0, 1).also { it.start(true) }
        var last: Int? = -1
        var fired = 0
        suspend fun effect() = beforeStepRenderEffect(0, 1, p, { last }, { last = it }) { fired++ }
        assertTrue(effect())
        assertFalse(effect()) // plain recreation: guarded
        p.needsRefire = true
        assertTrue(effect()) // cancelled before replying: re-fired
        assertEquals(2, fired)
        assertFalse(beforeStepRenderEffect(1, 1, p, { last }, { last = it }) { fired++ }) // past the end
        // No ViewModel → no guard.
        p.needsRefire = false
        assertTrue(beforeStepRenderEffect(0, 1, p, { null }, {}) { fired++ })
    }

    // ── iOS cross-findings ──────────────────────────────────────────────────────────────────────

    @Test
    fun `leaving a step before the delegate replies still fires onStepViewed exactly once`() = runTest {
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.name}}"), id = "a")
        val b = step(mapOf("id" to "t2", "type" to "text", "text" to "plain"), id = "b")
        val presentations = HostDataPresentations()
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false)
        var viewed = 0
        val never = CompletableDeferred<StepConfigOverride?>()
        val job = launch {
            runBeforeStepRenderHook(pa, backgroundScope, call = { never.await() }, onOverride = {},
                onStepViewed = { viewed++ }, stillPresented = { presentations.current === pa })
        }
        runCurrent()
        assertEquals(0, viewed)
        presentations.presentationFor(b, 1, hasDelegate = true, hasCachedOverride = false) // user moves on
        job.cancel(); runCurrent()
        assertEquals(1, viewed)
        assertTrue(pa.stepViewedFired)
    }

    @Test
    fun `a recreation cancel does not fire onStepViewed - the re-fire does, once`() = runTest {
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.name}}"), id = "a")
        val presentations = HostDataPresentations()
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false)
        var viewed = 0
        val first = launch {
            runBeforeStepRenderHook(pa, backgroundScope, call = { CompletableDeferred<StepConfigOverride?>().await() }, onOverride = {},
                onStepViewed = { viewed++ }, stillPresented = { presentations.current === pa })
        }
        runCurrent(); first.cancel(); runCurrent()
        assertEquals(0, viewed)
        assertTrue(pa.needsRefire)
        assertTrue(pa.pending)
        val reply = CompletableDeferred<StepConfigOverride?>()
        launch {
            runBeforeStepRenderHook(pa, backgroundScope, call = { reply.await() }, onOverride = {},
                onStepViewed = { viewed++ }, stillPresented = { presentations.current === pa })
        }
        runCurrent(); reply.complete(null); runCurrent()
        assertEquals(1, viewed)
        assertFalse(pa.pending)
    }

    @Test
    fun `a step that navigates to itself keeps its presentation, which the running call ends`() {
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.name}}"), id = "a")
        val presentations = HostDataPresentations()
        val p1 = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false)
        // GoToIndex(currentIndex) writes the same value: no new LaunchedEffect, the same presentation,
        // whose in-flight call (or deadline) ends pending — never a "Loading…" with no call behind it.
        assertSame(p1, presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false))
        p1.end(p1.nextGeneration())
        assertFalse(p1.pending)
    }

    @Test
    fun `a huge repeat max is capped, a fractional one falls back to the default, neither crashes`() {
        val items = (0 until 60).map { mapOf("name" to "n$it") }
        fun generated(max: Any): Int {
            val raw = mapOf(
                "id" to "sel", "type" to "input_select", "field_id" to "sel",
                "field_options" to listOf(mapOf("id" to "tpl", "value" to "v", "label" to "L",
                    // Distinct stored answers — the duplicate rule would otherwise collapse them.
                    "data_templates" to mapOf("label" to "{{item.name}}", "value" to "{{item.name}}"))),
                "field_config" to mapOf("repeat" to mapOf("source" to "hook_data.items", "template_option_id" to "tpl", "max" to max)),
            )
            val r = HostDataResolver.resolveRawBlock(raw, HostDataContext(stepId = "s", hookData = mapOf("items" to items)))
            return (r.block["field_options"] as List<*>).size
        }
        assertEquals(HostDataResolver.REPEAT_HARD_MAX, generated(1e30))
        assertEquals(HostDataResolver.REPEAT_HARD_MAX, generated(Double.MAX_VALUE))
        assertEquals(HostDataResolver.REPEAT_DEFAULT_MAX, generated(1.5))
        assertEquals(HostDataResolver.REPEAT_DEFAULT_MAX, generated(Double.NaN))
    }

    @Test
    fun `host options are resolved once - a substituted value containing a token is not re-scanned`() {
        val s = step(
            mapOf("id" to "sel", "type" to "input_select", "field_id" to "sel",
                "field_options" to listOf(mapOf("id" to "o1", "value" to "o1", "label" to "plain"))),
        )
        val p = HostDataStepPipeline(s, legacyContext = { null })
        val hookData = mapOf("a" to "{{hook_data.b}}", "b" to "LEAKED")
        val hostOpt = InputOption(value = "h", label = "{{hook_data.a}}", id = "h")
        val viaOverride = p.resolve(HostDataInputs(hookData = hookData, stepOverride = StepConfigOverride(dataContext = hookData, fieldOptions = mapOf("sel" to listOf(hostOpt)))))
        assertEquals("{{hook_data.b}}", viaOverride.blocks.single().field_options!!.single().label)
        val viaInteraction = p.resolve(HostDataInputs(hookData = hookData, fieldOptionsOverrides = mapOf("sel" to listOf(hostOpt))))
        assertEquals("{{hook_data.b}}", viaInteraction.blocks.single().field_options!!.single().label)
    }

    // ── m1: the dropdown label falls back to the recorded pick ────────────────────────

    @Test
    fun `r2 m1 dropdown label - rendered option, then the recorded pick, then the placeholder`() {
        val opts = listOf(opt("p", "Paris"))
        val rec = mapOf("value" to "x", "label" to "Xavier")
        assertEquals("Paris", dropdownSelectedLabel(opts, "p", rec, "Pick"))
        // A search result not in the cached pages (or excluded by the active query): the recorded label.
        assertEquals("Xavier", dropdownSelectedLabel(opts, "x", rec, "Pick"))
        // A record for a DIFFERENT value is not this value's label.
        assertEquals("Pick", dropdownSelectedLabel(opts, "y", rec, "Pick"))
        // Pruned / nothing picked: the placeholder, even with a stale record around.
        assertEquals("Pick", dropdownSelectedLabel(opts, "", rec, "Pick"))
        assertEquals("Pick", dropdownSelectedLabel(opts, "x", null, "Pick"))
    }

    @Test
    fun `r2 m1 dropdown - a picked value missing from the list shows its recorded label, a pruned one the placeholder`() {
        // The Option Set search case: the user picked `x` from search results, then cleared the query —
        // the list on screen (cached pages) does not contain it, but it IS the answer.
        val inputValues = mutableStateMapOf<String, Any>("city" to "x")
        SelectedOptionStore.record("city", opt("x", "Xavier"))
        compose.setContent {
            ContentBlockRendererView(
                blocks = listOf(block("city", "input_select", listOf(opt("p", "Paris")), placeholder = "Pick a city")),
                onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues,
            )
        }
        compose.onNodeWithText("Xavier").assertExists()
        // Prune: the value leaves inputValues and the record is cleared → the placeholder.
        compose.runOnIdle { inputValues.remove("city"); SelectedOptionStore.clear("city") }
        compose.onNodeWithText("Pick a city").assertExists()
        compose.onNodeWithText("Xavier").assertDoesNotExist()
    }

    // ── m2: onStepViewed is not lost for a presentation that never started pending ────

    @Test
    fun `r2 m2 a recreation cancel of a never-pending presentation re-fires, and onStepViewed fires once`() = runTest {
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.name}}"), id = "a")
        val presentations = HostDataPresentations()
        // A revisit with a cached override: not pending (start(false) ends it at once), delegate re-called.
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = true)
        assertFalse(pa.pending)
        assertTrue(pa.ended)
        var viewed = 0
        var last: Int? = -1
        var calls = 0
        suspend fun fire(call: suspend () -> StepConfigOverride?) = runBeforeStepRenderHook(
            pa, backgroundScope, call = { calls++; call() }, onOverride = {},
            onStepViewed = { viewed++ }, stillPresented = { presentations.current === pa },
        )
        val first = launch { beforeStepRenderEffect(0, 1, pa, { last }, { last = it }) { fire { CompletableDeferred<StepConfigOverride?>().await() } } }
        runCurrent(); first.cancel(); runCurrent() // Activity recreated before the reply
        assertEquals(0, viewed)
        assertTrue("a cancelled never-pending call still owes onStepViewed → re-fire", pa.needsRefire)
        val reply = CompletableDeferred<StepConfigOverride?>()
        var refired = false
        launch { refired = beforeStepRenderEffect(0, 1, pa, { last }, { last = it }) { fire { reply.await() } } }
        runCurrent(); reply.complete(null); runCurrent()
        assertTrue(refired)
        assertEquals(2, calls)
        assertEquals(1, viewed)
        // A later plain recreation: guarded, no third call, no second onStepViewed.
        assertFalse(beforeStepRenderEffect(0, 1, pa, { last }, { last = it }) { fire { null } })
        assertEquals(1, viewed)
        assertFalse(pa.needsRefire)
    }

    @Test
    fun `r2 m2 the flow ending mid-call pays onStepViewed first, exactly once`() = runTest {
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "plain"), id = "a")
        val presentations = HostDataPresentations()
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = true)
        val order = mutableListOf<String>()
        val job = launch {
            runBeforeStepRenderHook(pa, backgroundScope, call = { CompletableDeferred<StepConfigOverride?>().await() }, onOverride = {},
                onStepViewed = { order += "viewed" }, stillPresented = { presentations.current === pa })
        }
        runCurrent()
        // What the Activity's onFlowCompleted / onFlowDismissed do before the host callback + reset().
        presentations.current?.fireStepViewedIfOwed()
        order += "completed"
        presentations.reset()
        job.cancel(); runCurrent()
        assertEquals(listOf("viewed", "completed"), order)
        assertFalse(pa.needsRefire)
        // Nothing owed once fired, and nothing in flight → a no-op.
        pa.fireStepViewedIfOwed()
        assertEquals(1, order.count { it == "viewed" })
        HostDataPresentation("z", 0, 9).also { it.start(false) }.fireStepViewedIfOwed() // no call → no-op
    }

    @Test
    fun `r2 m2 a pending presentation cancelled by recreation still re-fires after onStepViewed already fired`() = runTest {
        // The override is still owed even when the step view was already reported (a second recreation).
        val a = step(mapOf("id" to "t", "type" to "text", "text" to "{{hook_data.name}}"), id = "a")
        val presentations = HostDataPresentations()
        val pa = presentations.presentationFor(a, 0, hasDelegate = true, hasCachedOverride = false)
        pa.stepViewedFired = true
        val job = launch {
            runBeforeStepRenderHook(pa, backgroundScope, call = { CompletableDeferred<StepConfigOverride?>().await() }, onOverride = {},
                onStepViewed = {}, stillPresented = { presentations.current === pa })
        }
        runCurrent(); job.cancel(); runCurrent()
        assertTrue(pa.pending)
        assertTrue(pa.needsRefire)
    }

    // ── iOS cross-checks ──────────────────────────────────────────────────────────────

    @Test
    fun `r2 a step token on a NESTED sheet block resolves from that block's own sheet_step_paths`() {
        val raw = mapOf(
            "id" to "tours", "type" to "input_select", "field_id" to "tour",
            "field_options" to listOf(mapOf(
                "id" to "opt", "label" to "Tour", "value" to "tour",
                "data_templates" to mapOf("label" to "{{item.name}}", "value" to "{{item.id}}"),
                "sheet_blocks" to listOf(
                    mapOf("id" to "sh_row", "type" to "row", "children" to listOf(
                        mapOf("id" to "sh_child", "type" to "text", "text" to "Total {{step.party_size}}"),
                    )),
                ),
            )),
            "field_config" to mapOf("repeat" to mapOf("source" to "hook_data.tours", "template_option_id" to "opt")),
        )
        val r = HostDataResolver.resolveRawBlock(raw, HostDataContext(stepId = "s", hookData = mapOf("tours" to listOf(mapOf("id" to "t1", "name" to "Garden walk")))))
        @Suppress("UNCHECKED_CAST")
        val sheetRaw = ((r.block["field_options"] as List<*>).single() as Map<*, *>)["sheet_blocks"] as List<Map<String, Any?>>
        // The raw pass leaves the token alone and records the path on the CHILD, not the row.
        val rowRaw = sheetRaw.single()
        assertNull((rowRaw["field_config"] as? Map<*, *>)?.get("sheet_step_paths"))
        val sheetBlocks = resolveSheetBlocks(sheetRaw, mapOf("party_size" to "3"))
        assertEquals("Total 3", sheetBlocks.single().children!!.single().text)
        // And the sheet renders it: raw-resolved, so no view-level pass could have produced "3".
        compose.setContent {
            CompositionLocalProvider(LocalHostDataRawResolved provides true) {
                ContentBlockRendererView(blocks = sheetBlocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
            }
        }
        compose.onNodeWithText("Total 3").assertExists()
    }

    @Test
    fun `r2 a nested scoped select loses a vanished selection`() {
        val inputValues = mutableMapOf<String, Any>("sel" to "gone", "multi" to listOf("a", "gone"))
        val sel = block("sel", "input_select", listOf(opt("a")), mapOf("resolve_state" to "scoped"))
        val multi = block("multi", "input_select", listOf(opt("a")), mapOf("resolve_state" to "empty_in_scope", "multi_select" to true))
        val row = ContentBlock(id = "row", type = "row", children = persistentListOf(sel, ContentBlock(id = "inner", type = "row", children = persistentListOf(multi))))
        assertTrue(pruneHostDataSelections(listOf(row), setOf("row", "sel", "inner", "multi"), inputValues))
        assertNull(inputValues["sel"])
        assertEquals(listOf("a"), inputValues["multi"])
    }

    @Test
    fun `r2 segmented default - only when nothing is answered, never over a non-string answer`() {
        val opts = listOf(opt("x"), opt("y"))
        assertEquals("x", segmentedDefault(null, opts))
        assertEquals("x", segmentedDefault("", opts))
        assertNull(segmentedDefault("y", opts))
        assertNull(segmentedDefault(3, opts))
        assertNull(segmentedDefault(null, emptyList()))
    }

    @Test
    fun `r2 segmented - options that arrive after pending get the first-option default`() {
        val inputValues = mutableStateMapOf<String, Any>()
        val shown = mutableStateOf(listOf(block("seg", "input_segmented", emptyList())))
        compose.setContent {
            ContentBlockRendererView(blocks = shown.value, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        assertNull(inputValues["seg"])
        compose.runOnIdle { shown.value = listOf(block("seg", "input_segmented", listOf(opt("x"), opt("y")))) }
        compose.waitForIdle()
        assertEquals("x", inputValues["seg"])
    }

    @Test
    fun `r2 segmented and select do not clobber a host-patched non-string answer`() {
        val inputValues = mutableStateMapOf<String, Any>("seg" to 3, "sel" to 4.5)
        val shown = mutableStateOf(listOf(
            block("seg", "input_segmented", listOf(opt("x"), opt("y"))),
            block("sel", "input_select", listOf(opt("a"), opt("b")), mapOf("display_style" to "stacked")),
        ))
        compose.setContent {
            ContentBlockRendererView(blocks = shown.value, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
        // A re-resolve (options re-arrive) must not resync either view's selection over the answer.
        compose.runOnIdle { shown.value = listOf(
            block("seg", "input_segmented", listOf(opt("x"), opt("y"), opt("z"))),
            block("sel", "input_select", listOf(opt("a"), opt("b"), opt("c")), mapOf("display_style" to "stacked")),
        ) }
        compose.waitForIdle()
        assertEquals(3, inputValues["seg"])
        assertEquals(4.5, inputValues["sel"])
    }

    // ── markers honoured only on raw-resolved blocks ───────────────────────────

    private val authoredMarkers = mapOf(
        "resolve_state" to "empty_in_scope",
        "empty_state" to mapOf("mode" to "text", "text" to "Nothing to pick"),
    )

    @Test
    fun `r5 a Screen or SDUI block's authored markers never relax the gate`() {
        // A block that never took the raw pass (a Screen / SDUI / consent-CTA caller): typed, authored
        // `resolve_state: empty_in_scope` on a required Select with 0 options.
        val sel = block("sel", "input_select", emptyList(), authoredMarkers + mapOf("hide_when_empty" to true)).copy(field_required = true)
        assertNull(RequiredFieldGate.resolveState(sel, emptySet()))
        assertNull(sel.hostDataEmptyState(emptySet()))
        val gate = RequiredFieldGate.evaluateDetailed(listOf(sel), emptyMap())
        assertFalse(gate.canAdvance)
        assertEquals("validation", gate.toastKind)
        assertFalse(RequiredFieldGate.evaluate(listOf(sel), emptyMap()).first)
        // Only a raw-resolved block's marker counts.
        assertTrue(RequiredFieldGate.evaluate(listOf(sel), emptyMap(), setOf("sel")).first)
        assertEquals("text" to "Nothing to pick", sel.hostDataEmptyState(setOf("sel")))
    }

    @Test
    fun `r5 the pipeline reports raw-resolved ids - a skipped block is not one, and its authored markers are stripped at capture`() {
        val s = step(
            mapOf("id" to "sel", "type" to "input_select", "field_id" to "sel", "field_required" to true,
                "field_options" to listOf(mapOf("id" to "a", "label" to "A", "value" to "a")),
                "field_config" to authoredMarkers),
            mapOf("id" to "title", "type" to "text", "text" to "Hi {{hook_data.name}}"),
        )
        val r = HostDataStepPipeline(s, legacyContext = { null }).resolve(HostDataInputs(hookData = mapOf("name" to "Ana")))
        val sel = r.blocks.first { it.id == "sel" }
        assertFalse("sel" in r.rawResolvedIds) // skipped the raw pass (no token)
        assertTrue("title" in r.rawResolvedIds)
        assertNull(sel.field_config?.get("resolve_state"))
        assertFalse(RequiredFieldGate.evaluate(r.blocks, emptyMap(), r.rawResolvedIds).first)
    }

    @Test
    fun `r5 a Select outside a raw-resolved step does not show an authored empty_state text`() {
        val sel = block("sel", "input_select", emptyList(), authoredMarkers + mapOf("display_style" to "stacked"), fieldLabel = "Pick")
        val raw = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalHostDataRawResolved provides raw.value,
                LocalHostDataRawResolvedIds provides setOf("sel"),
            ) {
                ContentBlockRendererView(blocks = listOf(sel), onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
            }
        }
        compose.onNodeWithText("Nothing to pick").assertDoesNotExist()
        compose.runOnIdle { raw.value = true }
        compose.onNodeWithText("Nothing to pick").assertExists()
    }
}
