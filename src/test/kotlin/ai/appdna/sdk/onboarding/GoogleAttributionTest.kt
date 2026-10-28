package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.TemplateContext
import ai.appdna.sdk.core.TemplateEngine
import ai.appdna.sdk.core.TemplateSurface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google's attribution, below the renderer: the step credit's walk, the TemplateEngine surface rule
 * and the location holder's logo flag. The Android half of `GoogleAttributionTests.swift`; the
 * rendered half is `GoogleAttributionRenderTest`. All place data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleAttributionTest {

    // ---- Synthetic answers -----------------------------------------------------------------------

    private val google: Map<String, Any> = mapOf(
        "formatted_address" to "Springfield, IL, USA", "address" to "Springfield, IL, USA",
        "city" to "Springfield", "state" to "Illinois", "country" to "United States", "country_code" to "US",
        "latitude" to 39.78, "longitude" to -89.65, "raw_query" to "Springf", "place_id" to "synthetic-place",
    )

    /** The SAME display values as [google], from Mapbox. */
    private val mapbox: Map<String, Any> = google - "place_id" + ("mapbox_id" to "synthetic-mapbox")

    /** A selection stored before provenance ids existed. */
    private val legacy: Map<String, Any> = google - "place_id"

    private fun answers(
        accumulated: Map<String, Any?> = emptyMap(),
        step: Map<String, Any?> = emptyMap(),
        store: Map<String, Any?> = emptyMap(),
    ) = GoogleCredit.Answers(accumulated = accumulated, stepInputs = step, sessionStore = store)

    private fun home(answer: Map<String, Any>) = mapOf("places" to mapOf("home" to answer))

    private fun step(json: Map<String, Any>): OnboardingStep =
        OnboardingConfigParser.parseStepForTest(json) ?: error("step did not parse")

    private fun blockStep(vararg blocks: Map<String, Any>) =
        step(mapOf("id" to "s", "type" to "custom", "config" to mapOf("content_blocks" to blocks.toList())))

    private fun shows(
        step: OnboardingStep,
        answers: GoogleCredit.Answers,
        override: StepConfigOverride? = null,
        patches: Map<String, Map<String, Any>> = emptyMap(),
        options: Map<String, List<InputOption>> = emptyMap(),
    ) = GoogleCredit.shows(step, override, patches, options, answers)

    // ---- The credit walk -------------------------------------------------------------------------

    /** A content-block token reaching a Google answer credits; the same token on Mapbox or legacy does not. */
    @Test
    fun aContentBlockTokenCreditsOnlyAGoogleAnswer() {
        val s = blockStep(mapOf("id" to "t", "type" to "text", "text" to "From {{responses.places.home.city}}"))
        assertTrue(shows(s, answers(accumulated = home(google))))
        assertFalse(shows(s, answers(accumulated = home(mapbox))))
        assertFalse(shows(s, answers(accumulated = home(legacy))))
    }

    /** RED CONTROL: a walk that skips nested arrays misses a question option's label. */
    @Test
    fun aQuestionOptionLabelCredits() {
        val s = step(
            mapOf(
                "id" to "q", "type" to "question",
                "config" to mapOf("title" to "Next?", "options" to listOf(mapOf("id" to "a", "label" to "Stay in {{input.home.city}}"))),
            ),
        )
        assertTrue(shows(s, answers(store = home(google))))
    }

    /** RED CONTROL: ignoring the override fails this — the step itself holds no token. */
    @Test
    fun aTokenInTheConfigOverrideCredits() {
        val s = step(mapOf("id" to "w", "type" to "welcome", "config" to mapOf("title" to "Welcome")))
        val a = answers(accumulated = home(google))
        assertFalse("control: the step alone holds no token", shows(s, a))
        assertTrue(shows(s, a, override = StepConfigOverride(title = "Welcome to {{responses.places.home.city}}")))
    }

    /** The interaction patches are walked with the step: a field_config patch and a replacement option list. */
    @Test
    fun aTokenInAnInteractionPatchCredits() {
        val s = blockStep(mapOf("id" to "sel", "type" to "input_select", "field_id" to "pick", "field_options" to listOf(mapOf("id" to "a", "label" to "A"))))
        val a = answers(accumulated = home(google))
        assertFalse("control: the step alone holds no token", shows(s, a))
        assertTrue(shows(s, a, patches = mapOf("sel" to mapOf("search_placeholder" to "Near {{responses.places.home.city}}"))))
        // A host's replacement options are TYPED: walked through the option's own rendering.
        val replaced = StepConfigOverride.decodeFieldOptions(
            mapOf("sel" to listOf(mapOf("id" to "b", "label" to "Near {{responses.places.home.city}}"))),
        )!!
        assertTrue(shows(s, a, options = replaced))
    }

    /** A visibility condition names its variable without braces and outside `bindings`: no credit. */
    @Test
    fun aVisibilityConditionAloneDoesNotCredit() {
        val s = blockStep(
            mapOf(
                "id" to "t", "type" to "text", "text" to "Nice to meet you",
                "visibility_condition" to mapOf("type" to "when_not_empty", "variable" to "responses.places.home.place_id"),
            ),
        )
        assertFalse(shows(s, answers(accumulated = home(google), store = home(google))))
    }

    /** RED CONTROL: skipping `bindings` fails this. */
    @Test
    fun aTextBoundThroughBindingsCredits() {
        val s = blockStep(mapOf("id" to "t", "type" to "text", "text" to "x", "bindings" to mapOf("text" to "responses.places.home.city")))
        assertTrue(shows(s, answers(accumulated = home(google))))
        assertFalse(shows(s, answers(accumulated = home(mapbox))))
    }

    private val sheetStep: OnboardingStep
        get() = blockStep(
            mapOf(
                "id" to "sel", "type" to "input_select", "field_id" to "pick",
                "field_options" to listOf(
                    mapOf(
                        "id" to "near", "label" to "Near me",
                        "sheet_blocks" to listOf(mapOf("id" to "sh", "type" to "text", "text" to "Close to {{responses.places.home.city}}")),
                    ),
                    mapOf("id" to "far", "label" to "Far away"),
                ),
            ),
        )

    private fun sheetContext(s: OnboardingStep, a: GoogleCredit.Answers, options: Map<String, List<InputOption>> = emptyMap()) =
        GoogleCreditSheetContext(sources = { GoogleCredit.stepSources(s, null, emptyMap(), options) }, answers = a)

    /** RED CONTROL: a step walk that does not skip `sheet_blocks` credits the step. */
    @Test
    fun aTokenInAnOptionSheetCreditsTheSheetNotTheStep() {
        val s = sheetStep
        val a = answers(accumulated = home(google))
        assertFalse("the step itself does not credit", shows(s, a))
        val ctx = sheetContext(s, a)
        assertTrue("the sheet does", ctx.shows("sel", "near", emptyList(), emptyMap()))
        assertFalse("a sheet without the token does not", ctx.shows("sel", "far", emptyList(), emptyMap()))
    }

    /** A sheet the host supplied (typed options, no published source) is walked from its typed blocks. */
    @Test
    fun aHostSuppliedOptionSheetCredits() {
        val s = blockStep(mapOf("id" to "sel", "type" to "input_select", "field_id" to "pick"))
        val a = answers(accumulated = home(google))
        val hostOptions = StepConfigOverride.decodeFieldOptions(
            mapOf(
                "sel" to listOf(
                    mapOf(
                        "id" to "near", "label" to "Near me",
                        "sheet_blocks" to listOf(mapOf("id" to "sh", "type" to "text", "text" to "Close to {{responses.places.home.city}}")),
                    ),
                ),
            ),
        )!!
        assertFalse("the step does not credit for a sheet", shows(s, a, options = hostOptions))
        assertTrue(sheetContext(s, a, hostOptions).shows("sel", "near", emptyList(), emptyMap()))
        // A sheet found nowhere in the sources walks the blocks it was presented with.
        val presented = hostOptions.getValue("sel").single().sheet_blocks!!.toList()
        assertTrue(sheetContext(s, a).shows("other", "x", presented, emptyMap()))
        assertFalse(sheetContext(s, answers(accumulated = home(mapbox))).shows("other", "x", presented, emptyMap()))
    }

    /** A sheet's `step` root reads the SHEET's own inputs, not the step's. */
    @Test
    fun aSheetStepRootReadsTheSheetInputs() {
        val s = blockStep(
            mapOf(
                "id" to "sel", "type" to "input_select", "field_id" to "pick",
                "field_options" to listOf(
                    mapOf("id" to "o", "label" to "O", "sheet_blocks" to listOf(mapOf("id" to "sh", "type" to "text", "text" to "{{step.where.city}}"))),
                ),
            ),
        )
        val stepHoldsIt = sheetContext(s, answers(step = mapOf("where" to google)))
        assertFalse("the step's inputs are not the sheet's", stepHoldsIt.shows("sel", "o", emptyList(), emptyMap()))
        assertTrue(stepHoldsIt.shows("sel", "o", emptyList(), mapOf("where" to google)))
    }

    /**
     * RED CONTROL: a legacy step shown FIRST in a re-presented flow — nothing accumulated, the session
     * store still holding the previous run's Google answer — credits.
     */
    @Test
    fun aRepresentedFlowsFirstStepCreditsTheSessionStore() {
        val s = step(mapOf("id" to "w", "type" to "welcome", "config" to mapOf("title" to "Back in {{responses.places.home.city}}?")))
        assertTrue(shows(s, answers(store = home(google))))
    }

    /** Each legacy view's text: a form title, a value-prop item, a custom subtitle and a field placeholder. */
    @Test
    fun everyLegacyViewsTextCredits() {
        val a = answers(store = home(google))
        val steps = listOf(
            mapOf("id" to "f", "type" to "form", "config" to mapOf("title" to "Home: {{onboarding.places.home.city}}", "fields" to listOf(mapOf("id" to "n", "type" to "text", "label" to "N")))),
            mapOf("id" to "v", "type" to "value_prop", "config" to mapOf("items" to listOf(mapOf("icon" to "star", "title" to "L", "subtitle" to "For {{onboarding.places.home.city}}")))),
            mapOf("id" to "c", "type" to "custom", "config" to mapOf("subtitle" to "To {{onboarding.places.home.country}}")),
            mapOf("id" to "p", "type" to "form", "config" to mapOf("fields" to listOf(mapOf("id" to "st", "type" to "text", "label" to "S", "placeholder" to "In {{onboarding.places.home.city}}")))),
        )
        for (json in steps) assertTrue("${json["id"]} credits", shows(step(json), a))
    }

    /** A step that templates nothing never credits, whatever answers exist. */
    @Test
    fun aStepWithoutTokensNeverCredits() {
        val s = blockStep(mapOf("id" to "t", "type" to "text", "text" to "Hello"))
        assertFalse(shows(s, answers(accumulated = home(google), step = mapOf("w" to google), store = home(google))))
    }

    /** A step built in code (no published source) is walked through its typed config. */
    @Test
    fun aStepBuiltInCodeIsWalkedThroughItsTypedConfig() {
        val built = OnboardingStep(
            id = "code", type = OnboardingStep.StepType.WELCOME,
            config = StepConfig(title = "Hi from {{responses.places.home.city}}"),
        )
        assertTrue(shows(built, answers(accumulated = home(google))))
        assertFalse(shows(built.copy(config = StepConfig(title = "Hi")), answers(accumulated = home(google))))
    }

    // ---- The TemplateEngine surface rule ---------------------------------------------------------

    private fun context(surface: TemplateSurface?) = TemplateContext(
        userTraits = null, remoteConfig = { null },
        onboardingResponses = mapOf("places" to mapOf("home" to google, "work" to mapbox)),
        computedData = emptyMap(), sessionData = emptyMap(), deviceInfo = emptyMap(),
    ).let { if (surface == null) it else it.copy(surface = surface) }

    /** RED CONTROL: an engine that ignores the surface prints the Google city here. */
    @Test
    fun offOnboardingAGoogleAnswerIsTheTypedText() {
        val ctx = context(TemplateSurface.OTHER)
        assertEquals("Springf", TemplateEngine.interpolate("{{responses.places.home.city}}", ctx))
        assertEquals("Springf", TemplateEngine.interpolate("{{responses.places.home}}", ctx))
        assertEquals("Springf", TemplateEngine.interpolate("{{input.home}}", ctx))
    }

    @Test
    fun offOnboardingAMapboxAnswerKeepsItsCity() {
        assertEquals("Springfield", TemplateEngine.interpolate("{{responses.places.work.city}}", context(TemplateSurface.OTHER)))
    }

    @Test
    fun onOnboardingAGoogleAnswerKeepsItsCity() {
        assertEquals("Springfield", TemplateEngine.interpolate("{{responses.places.home.city}}", context(TemplateSurface.ONBOARDING)))
    }

    /** A value holding a Google answer, stringified off onboarding, never carries the answer's content. */
    @Test
    fun offOnboardingAValueHoldingAGoogleAnswerIsRedacted() {
        val ctx = context(TemplateSurface.OTHER).let {
            it.copy(onboardingResponses = it.onboardingResponses + ("trip" to mapOf("stops" to listOf(google, mapbox))))
        }
        val text = TemplateEngine.interpolate("{{responses.trip.stops}}", ctx)
        // The Mapbox answer carries the same display values, so the Google one is told apart by its id.
        assertFalse("no Google value: $text", text.contains("synthetic-place"))
        assertTrue("the typed text stands in for it: $text", text.startsWith("[Springf, "))
        assertTrue("the Mapbox answer is still there: $text", text.contains("synthetic-mapbox"))
        // Control: on onboarding the same token keeps the Google values.
        val on = TemplateEngine.interpolate("{{responses.trip.stops}}", ctx.copy(surface = TemplateSurface.ONBOARDING))
        assertTrue("control: $on", on.contains("synthetic-place"))
    }

    /** A hand-built context defaults to the restrictive surface. */
    @Test
    fun aHandBuiltContextDefaultsToOther() {
        assertEquals(TemplateSurface.OTHER, context(null).surface)
        assertEquals("Springf", TemplateEngine.interpolate("{{responses.places.home.city}}", context(null)))
    }

    // ---- The holder ------------------------------------------------------------------------------

    private fun row(provider: String) = JSONObject()
        .put("id", "synthetic-$provider").put("provider", provider)
        .put("formatted_address", "Springfield, IL, USA").put("name", "Springfield").put("place_formatted", "IL, USA")

    /** The holder shows the logo for a Google list only, and drops it with the list on a tap. */
    @Test
    fun theHolderShowsTheLogoForAGoogleListOnly() {
        val settings = LocationSearchSettings.from(null as Map<String, Any?>?, defaultMinChars = 3)
        for ((provider, expected) in listOf("google" to true, "mapbox" to false)) {
            val rig = LocationSearchRig(settings, null, mapOf("Springf" to JSONArray().put(row(provider))))
            rig.type("Springf")
            rig.advance(LocationSuggestionSearch.DEBOUNCE_MS)
            assertTrue("$provider: listed", rig.holder.isDropdownOpen)
            assertEquals("$provider: logo", expected, rig.holder.showsGoogleLogo)
            rig.tap(0)
            assertFalse("$provider: a tap drops the list and its logo", rig.holder.showsGoogleLogo)
        }
    }

    /** `carriesPlaceId`: a non-blank `place_id` only. */
    @Test
    fun onlyANonBlankPlaceIdIsAGoogleAnswer() {
        assertTrue(LocationAnswer.carriesPlaceId(google))
        assertFalse(LocationAnswer.carriesPlaceId(mapbox))
        assertFalse(LocationAnswer.carriesPlaceId(legacy))
        assertFalse(LocationAnswer.carriesPlaceId(legacy + ("place_id" to "  ")))
        assertFalse(LocationAnswer.carriesPlaceId("Springf"))
        assertFalse(LocationAnswer.carriesPlaceId(null))
    }
}
