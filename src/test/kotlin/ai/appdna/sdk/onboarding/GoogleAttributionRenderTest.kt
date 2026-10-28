package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.SessionDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Google's attribution, RENDERED: the logo under a Google dropdown, the logo kept beside a field that
 * holds a Google answer, and the "Google Maps" credit an onboarding step draws when its text templates
 * one. The Android half of the rendered tests in `GoogleAttributionTests.swift`.
 *
 * Every case renders the REAL composable twice — a Google answer (or list) and the SAME display values
 * from Mapbox — so the only thing that can differ is Google's attribution. The pixel cases compare two
 * live frames, and each carries its control: the harness must also be shown seeing a frame that does
 * not change. Only the geocoding socket is stubbed ([locationGeocodeTransport]). All place data is
 * synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class GoogleAttributionRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val line = "Springfield, IL, USA"
    private val google: Map<String, Any> = mapOf(
        "formatted_address" to line, "address" to line, "city" to "Springfield", "state" to "Illinois",
        "country" to "United States", "country_code" to "US", "latitude" to 39.78, "longitude" to -89.65,
        "raw_query" to "Springf", "place_id" to "synthetic-place",
    )
    private val mapbox: Map<String, Any> = google - "place_id" + ("mapbox_id" to "synthetic-mapbox")

    @Before
    fun enableCapture() {
        // Roborazzi writes only in record mode, and these tests compare their own frames.
        System.setProperty("roborazzi.test.record", "true")
    }

    @After
    fun reset() {
        locationGeocodeTransport = LiveGeocodeTransport
        SessionDataStore.instance?.clearAll()
    }

    // ---- Stubbed geocoding -----------------------------------------------------------------------

    /** Autocomplete answers one row from [provider]; resolve answers that row's resolved answer. */
    private class Stub(private val provider: String) : GeocodeTransport {
        override suspend fun post(url: String, headers: Map<String, String>, body: String): GeocodeHttpReply {
            if (url.endsWith("/resolve")) {
                val s = JSONObject().put("formatted_address", "Springfield, IL, USA").put("city", "Springfield")
                    .put("state", "Illinois").put("country", "United States").put("country_code", "US")
                    .put("latitude", 39.78).put("longitude", -89.65).put("provider", provider)
                if (provider == "google") s.put("place_id", "synthetic-place") else s.put("mapbox_id", "synthetic-mapbox")
                return GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("resolved", true).put("suggestion", s)).toString())
            }
            val row = JSONObject().put("id", "synthetic-$provider").put("provider", provider)
                .put("formatted_address", "Springfield, IL, USA").put("name", "Springfield").put("place_formatted", "IL, USA")
            return GeocodeHttpReply(200, JSONObject().put("data", JSONObject().put("suggestions", JSONArray().put(row))).toString())
        }
    }

    private fun formStep(): OnboardingStep = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "s_form", "type" to "form",
            "config" to mapOf("fields" to listOf(mapOf("id" to "where", "type" to "location", "label" to "Where"))),
        ),
    ) ?: error("form step did not parse")

    private fun locationBlocks(): List<ContentBlock> = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "s_block", "type" to "custom",
            "config" to mapOf(
                "content_blocks" to listOf(
                    mapOf(
                        "id" to "b_loc", "type" to "input_location", "field_id" to "where",
                        "field_config" to mapOf("location_min_chars" to 3, "dropdown_bg_color" to "#FFFFFF"),
                    ),
                ),
            ),
        ),
    )!!.config.content_blocks!!.toList()

    private fun logoShown() = compose.onAllNodes(hasTestTag(GoogleMapsAttribution.LOGO_TAG)).fetchSemanticsNodes().isNotEmpty()
    private fun logoBounds(): Rect = compose.onAllNodes(hasTestTag(GoogleMapsAttribution.LOGO_TAG)).fetchSemanticsNodes().single().boundsInRoot
    private fun rowBounds(): Rect = compose.onAllNodes(hasText("IL, USA")).fetchSemanticsNodes().first().boundsInRoot
    private fun creditShown() = compose.onAllNodes(hasTestTag(GoogleMapsAttribution.TEXT_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun typeAndList() {
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Springf")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun tapTheRow() {
        compose.onNodeWithText("IL, USA").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    // ---- The asset and the variant ---------------------------------------------------------------

    /** Every variant ships in the AAR under the name NOTICE.md records, at Google's 4x render size. */
    @Test
    fun everyVariantLoadsFromTheResources() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        for (v in GoogleMapsAttribution.Variant.entries) {
            assertEquals(v.resourceName, res.getResourceEntryName(v.drawable))
            val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
            val bmp = android.graphics.BitmapFactory.decodeResource(res, v.drawable, opts)
            assertTrue("${v.name} decodes", bmp != null && bmp.width > bmp.height && bmp.height >= 4 * 16)
        }
    }

    @Test
    fun theVariantFollowsTheSurface() {
        assertEquals(GoogleMapsAttribution.Variant.GRAY, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Plain(null), darkAppearance = false))
        assertEquals(GoogleMapsAttribution.Variant.WHITE, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Plain(null), darkAppearance = true))
        assertEquals(GoogleMapsAttribution.Variant.GRAY, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Plain("#FFFFFF"), darkAppearance = true))
        assertEquals(GoogleMapsAttribution.Variant.WHITE, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Plain("#111827"), darkAppearance = false))
        assertEquals(GoogleMapsAttribution.Variant.LIGHT_OUTLINE, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Busy, darkAppearance = false))
        assertEquals(GoogleMapsAttribution.Variant.DARK_OUTLINE, GoogleMapsAttribution.variant(GoogleMapsAttribution.Backdrop.Busy, darkAppearance = true))
        assertEquals(GoogleMapsAttribution.Backdrop.Busy, GoogleMapsAttribution.backdrop(ai.appdna.sdk.core.BackgroundStyleConfig(type = "image", image_url = "https://x")))
        // A colour with no `type` draws nothing of its own (the host paints the theme background), so it is
        // the theme's, not the colour's.
        assertEquals(GoogleMapsAttribution.Backdrop.Plain(null), GoogleMapsAttribution.backdrop(ai.appdna.sdk.core.BackgroundStyleConfig(color = "#000000")))
        assertEquals("#5E5E5E", GoogleMapsAttribution.textColorHex(GoogleMapsAttribution.Backdrop.Plain(null), darkAppearance = false))
        assertEquals("#FFFFFF", GoogleMapsAttribution.textColorHex(GoogleMapsAttribution.Backdrop.Busy, darkAppearance = false))
    }

    /**
     * What each host actually draws behind the attribution. A chat step is dark in a light theme; a
     * Screen draws its own colour; a Screen section draws its own fill over the screen's.
     */
    @Test
    fun theBackdropIsWhatTheHostDraws() {
        val chat = OnboardingConfigParser.parseStepForTest(mapOf("id" to "chat", "type" to "interactive_chat", "config" to emptyMap<String, Any>()))!!
        val chatBackdrop = GoogleMapsAttribution.backdrop(ofStep = chat.config, type = chat.type)
        assertEquals(GoogleMapsAttribution.Backdrop.Plain("#0F172A"), chatBackdrop)
        assertEquals(GoogleMapsAttribution.Variant.WHITE, GoogleMapsAttribution.variant(chatBackdrop, darkAppearance = false))
        val welcome = OnboardingConfigParser.parseStepForTest(mapOf("id" to "w", "type" to "welcome", "config" to mapOf("title" to "Hi")))!!
        assertEquals("the theme's background", GoogleMapsAttribution.Backdrop.Plain(null), GoogleMapsAttribution.backdrop(ofStep = welcome.config, type = welcome.type))

        val screen = GoogleMapsAttribution.backdrop(ofScreen = ai.appdna.sdk.screens.BackgroundConfig(color = "#101828"))
        assertEquals(GoogleMapsAttribution.Variant.WHITE, GoogleMapsAttribution.variant(screen, darkAppearance = false))
        assertEquals(GoogleMapsAttribution.Backdrop.Plain(null), GoogleMapsAttribution.backdrop(ofScreen = null))
        val light = ai.appdna.sdk.screens.SectionStyle(backgroundColor = "#FFFFFF")
        assertEquals(GoogleMapsAttribution.Variant.GRAY, GoogleMapsAttribution.variant(GoogleMapsAttribution.backdrop(ofSection = light, behind = screen), darkAppearance = true))
        val gradient = ai.appdna.sdk.screens.SectionStyle(backgroundGradient = ai.appdna.sdk.screens.GradientConfig(start = "#000000", end = "#FFFFFF"))
        assertEquals(GoogleMapsAttribution.Backdrop.Busy, GoogleMapsAttribution.backdrop(ofSection = gradient, behind = screen))
        assertEquals("no fill: what is behind", screen, GoogleMapsAttribution.backdrop(ofSection = ai.appdna.sdk.screens.SectionStyle(), behind = screen))
        assertEquals("#FFFFFF", GoogleMapsAttribution.hexOf(Color.White))
    }

    /**
     * The logo DRAWS: a frame with it against the same frame with an empty box of the same place. The
     * control renders the empty box twice and must find no difference, or the harness measures itself.
     */
    @Test
    fun theLogoDrawsPixels() {
        fun frame(name: String, logo: Boolean): android.graphics.Bitmap {
            show {
                Box(Modifier.size(200.dp, 60.dp).background(Color.White)) {
                    if (logo) GoogleMapsLogo(GoogleMapsAttribution.Backdrop.Plain("#FFFFFF"))
                }
            }
            compose.waitForIdle()
            return capture("logo-$name")
        }
        val empty1 = frame("empty1", false)
        val empty2 = frame("empty2", false)
        val withLogo = frame("logo", true)
        assertEquals("control: two empty frames are identical", 0, diff(empty1, empty2).count)
        val d = diff(empty1, withLogo)
        assertTrue("the logo changed ${d.count} pixels", d.count > 200)
    }

    // ---- The dropdown ----------------------------------------------------------------------------

    /**
     * The form-step field: a Google list draws Google's logo under the rows; the same rows from Mapbox
     * do not. RED CONTROL: the logo removed from the dropdown → the Google case fails.
     */
    @Test
    fun theFormStepDropdownDrawsTheLogoForGoogleOnly() {
        for ((provider, expected) in listOf("google" to true, "mapbox" to false)) {
            locationGeocodeTransport = Stub(provider)
            show { FormStep(config = formStep().config, onNext = {}, savedValues = null) }
            compose.waitForIdle()
            typeAndList()
            assertTrue("$provider: the row is listed", compose.onAllNodes(hasText("IL, USA")).fetchSemanticsNodes().isNotEmpty())
            assertEquals("$provider: the logo", expected, logoShown())
            if (expected) assertTrue("the logo is under the row", logoBounds().top >= rowBounds().bottom)
        }
    }

    /** The `input_location` block: the same, with the authored dropdown colour. */
    @Test
    fun theBlockDropdownDrawsTheLogoForGoogleOnly() {
        for ((provider, expected) in listOf("google" to true, "mapbox" to false)) {
            locationGeocodeTransport = Stub(provider)
            val inputValues = mutableStateMapOf<String, Any>()
            show { ContentBlockRendererView(blocks = locationBlocks(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues) }
            compose.waitForIdle()
            typeAndList()
            assertTrue("$provider: the row is listed", compose.onAllNodes(hasText("IL, USA")).fetchSemanticsNodes().isNotEmpty())
            assertEquals("$provider: the logo", expected, logoShown())
            if (expected) assertTrue("the logo is under the row", logoBounds().top >= rowBounds().bottom)
        }
    }

    // ---- After the tap ---------------------------------------------------------------------------

    /**
     * A Google tap, through each view: the dropdown closes, the answer stored carries `place_id`, and the
     * field keeps the logo beside it. The same tap on a Mapbox row keeps none.
     * RED CONTROL: the field's attribution removed → the Google case fails.
     */
    @Test
    fun aTappedGoogleAnswerKeepsTheLogoBesideTheField() {
        for ((provider, expected) in listOf("google" to true, "mapbox" to false)) {
            locationGeocodeTransport = Stub(provider)
            val inputValues = mutableStateMapOf<String, Any>()
            show { ContentBlockRendererView(blocks = locationBlocks(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues) }
            compose.waitForIdle()
            typeAndList()
            tapTheRow()
            assertFalse("$provider: the dropdown closed", compose.onAllNodes(hasText("IL, USA")).fetchSemanticsNodes().isNotEmpty())
            assertEquals("$provider: the stored answer", expected, LocationAnswer.carriesPlaceId(inputValues["where"]))
            assertEquals("$provider: the field's logo", expected, logoShown())
        }
    }

    /**
     * A field restored with a held answer: Google keeps the logo, Mapbox does not — on both views. The
     * pixel pair proves the difference is the attribution BELOW the field and nothing else.
     */
    @Test
    fun aFieldHoldingAGoogleAnswerKeepsTheLogo() {
        fun form(answer: Map<String, Any>, name: String): android.graphics.Bitmap {
            show { FormStep(config = formStep().config, onNext = {}, savedValues = mapOf("where" to answer)) }
            compose.waitForIdle()
            return capture("form-$name")
        }
        val m1 = form(mapbox, "mapbox1")
        assertFalse("mapbox: no logo", logoShown())
        val field = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().single().boundsInRoot
        val m2 = form(mapbox, "mapbox2")
        val g = form(google, "google")
        assertTrue("google: the logo", logoShown())
        assertEquals("control: the same answer renders the same frame", 0, diff(m1, m2).count)
        val d = diff(m1, g)
        assertTrue("google vs mapbox differ (${d.count} px)", d.count > 0)
        assertTrue("every changed pixel is below the field (first row ${d.top}, field bottom ${field.bottom})", d.top >= field.bottom.toInt())

        for ((answer, expected) in listOf(google to true, mapbox to false)) {
            val inputValues = mutableStateMapOf<String, Any>("where" to answer)
            show { ContentBlockRendererView(blocks = locationBlocks(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues) }
            compose.waitForIdle()
            assertEquals("block restored: the logo", expected, logoShown())
        }
    }

    // ---- The step's credit -----------------------------------------------------------------------

    private fun home(answer: Map<String, Any>) = mapOf("places" to mapOf("home" to answer))

    /** Seeds the session store the legacy views' `interpolated()` reads, as a completed step would. */
    private fun seedStore(answer: Map<String, Any>) {
        SessionDataStore.initialize(ApplicationProvider.getApplicationContext())
        SessionDataStore.instance!!.setOnboardingResponses(home(answer))
    }

    private fun renderStep(step: OnboardingStep, answer: Map<String, Any>) {
        seedStore(answer)
        show {
            OnboardingStepView(
                step = step, effectiveConfig = step.config, onNext = {}, onSkip = {},
                accumulatedResponses = home(answer),
            )
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /**
     * Each step renders through the REAL step view — a legacy welcome heading, a legacy form title, a
     * legacy question option and a content block — with a Google answer and then a Mapbox answer whose text is identical. Only
     * the Google one draws the credit, below the step's content.
     * RED CONTROL: the credit removed from the step container → the Google cases fail.
     */
    @Test
    fun aStepTemplatingAGoogleAnswerDrawsTheCredit() {
        val steps = listOf(
            mapOf("id" to "w", "type" to "welcome", "config" to mapOf("title" to "Hello from {{responses.places.home.city}}", "cta_text" to "Start")) to "Start",
            // A legacy FORM step's title, through `FormStep`'s own `interpolated()`.
            mapOf(
                "id" to "f", "type" to "form",
                "config" to mapOf(
                    "title" to "Home: {{responses.places.home.city}}",
                    "fields" to listOf(mapOf("id" to "n", "type" to "text", "label" to "Nick", "required" to false)),
                ),
            ) to "Continue",
            mapOf(
                "id" to "q", "type" to "question",
                "config" to mapOf("title" to "Next?", "options" to listOf(mapOf("id" to "a", "label" to "Stay in {{input.home.city}}"), mapOf("id" to "b", "label" to "Go"))),
            ) to "Go",
            mapOf(
                "id" to "c", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf("id" to "t", "type" to "text", "text" to "Hello from {{responses.places.home.city}}"),
                        mapOf("id" to "b", "type" to "button", "text" to "Continue", "action" to "next"),
                    ),
                ),
            ) to "Continue",
        )
        // Every step is walked before anything is asserted, so a failure names EVERY step it hits.
        val misses = mutableListOf<String>()
        for ((json, lastText) in steps) {
            val id = json["id"]
            val step = OnboardingConfigParser.parseStepForTest(json)!!
            renderStep(step, mapbox)
            if (compose.onAllNodes(hasText("Springfield", substring = true)).fetchSemanticsNodes().isEmpty()) misses += "$id: the step's text did not resolve"
            if (creditShown()) misses += "$id: mapbox drew a credit"

            renderStep(step, google)
            if (!creditShown()) { misses += "$id: google drew no credit"; continue }
            val credit = compose.onAllNodes(hasTestTag(GoogleMapsAttribution.TEXT_TAG)).fetchSemanticsNodes().single().boundsInRoot
            val last = compose.onAllNodes(hasText(lastText, ignoreCase = true)).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
            if (credit.top < last) misses += "$id: the credit is not below the step's content"
        }
        assertTrue("every step credits a Google answer and only a Google answer: $misses", misses.isEmpty())
    }

    /** A token inside an option's sheet credits INSIDE the sheet when it opens, not on the step. */
    @Test
    fun anOptionSheetTemplatingAGoogleAnswerDrawsItsOwnCredit() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "sel", "type" to "input_select", "field_id" to "pick",
                            // A list: the dropdown presentation does not open option sheets.
                            "field_config" to mapOf("display_style" to "list"),
                            "field_options" to listOf(
                                mapOf(
                                    "id" to "near", "label" to "Near me",
                                    "sheet_blocks" to listOf(mapOf("id" to "sh", "type" to "text", "text" to "Close to {{responses.places.home.city}}")),
                                ),
                                mapOf("id" to "far", "label" to "Far away"),
                            ),
                        ),
                    ),
                ),
            ),
        )!!
        renderStep(step, google)
        assertFalse("the step itself draws no credit", creditShown())
        compose.onNodeWithText("Near me").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue("the sheet opened", compose.onAllNodes(hasText("Close to", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the sheet draws the credit", creditShown())
    }

    // ---- The variant follows the colour drawn, not the system appearance ---------------------------

    /** The `input_location` block with no authored dropdown colour: the dropdown takes the theme's. */
    private fun unstyledLocationBlocks(): List<ContentBlock> = OnboardingConfigParser.parseStepForTest(
        mapOf(
            "id" to "s_plain", "type" to "custom",
            "config" to mapOf(
                "content_blocks" to listOf(
                    mapOf("id" to "b_loc", "type" to "input_location", "field_id" to "where", "field_config" to mapOf("location_min_chars" to 3)),
                ),
            ),
        ),
    )!!.config.content_blocks!!.toList()

    /** Pixels inside the logo's bounds darker than mid-gray: the gray logo's letters on a light fill. */
    private fun darkPixelsInLogo(frame: android.graphics.Bitmap): Int {
        val b = logoBounds()
        var n = 0
        for (y in b.top.toInt() until b.bottom.toInt().coerceAtMost(frame.height)) {
            for (x in b.left.toInt() until b.right.toInt().coerceAtMost(frame.width)) {
                val c = frame.getPixel(x, y)
                val l = 0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)
                if (l < 160) n += 1
            }
        }
        return n
    }

    /**
     * The Screens host's shape: a bare light `MaterialTheme {}` on a device in dark mode. The dropdown is
     * filled with the light theme's surface, so Google's logo there is the GRAY variant — its letters
     * draw dark on the light fill. Deciding from the system appearance drew the WHITE variant: white on
     * near-white, no letters at all. The same for the logo kept beside the field once the dropdown
     * closes, drawn on the theme's background. RED CONTROL: `isSystemInDarkTheme()` for the variant →
     * no dark pixels in the logo.
     */
    @Test
    @Config(qualifiers = "+night")
    fun aLightThemeOnADarkDeviceDrawsTheGrayLogo() {
        locationGeocodeTransport = Stub("google")
        var systemDark = false
        val inputValues = mutableStateMapOf<String, Any>()
        show {
            systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            androidx.compose.material3.MaterialTheme {
                Box(Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                    ContentBlockRendererView(blocks = unstyledLocationBlocks(), onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
                }
            }
        }
        compose.waitForIdle()
        assertTrue("control: the device is in dark mode", systemDark)
        typeAndList()
        assertTrue("the Google list draws the logo", logoShown())
        val listed = darkPixelsInLogo(capture("dark-device-dropdown"))
        assertTrue("the dropdown's logo draws dark letters on the light fill ($listed px)", listed > 100)

        tapTheRow()
        assertTrue("the field keeps the logo after the tap", logoShown())
        val kept = darkPixelsInLogo(capture("dark-device-field"))
        assertTrue("the field's logo draws dark letters on the light background ($kept px)", kept > 100)
    }

    /**
     * A re-presentation of the same step, with the same override and answers, reads THIS presentation's
     * interaction patches. The step is shown under the flow host's wiring; a refresh reply patches a
     * Select with a Google token, and the credit appears. The same composition then gets a new
     * presentation (0→1→0 inside the exit tween), whose patch maps start empty: the credit must go with
     * them. RED CONTROL: the decision remembered on (step, override, answers) alone keeps reading the
     * previous presentation's patch, and the credit stays.
     */
    @Test
    fun aNewPresentationReadsItsOwnPatches() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "p", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf("id" to "sel", "type" to "input_select", "field_id" to "pick", "field_config" to mapOf("display_style" to "list"),
                            "field_options" to listOf(mapOf("id" to "a", "label" to "First"))),
                        mapOf("id" to "more", "type" to "button", "text" to "More", "action" to "refresh_step", "action_value" to "more"),
                    ),
                ),
            ),
        )!!
        val other = OnboardingConfigParser.parseStepForTest(mapOf("id" to "o", "type" to "custom", "config" to emptyMap<String, Any>()))!!
        val presentations = HostDataPresentations()
        val access = HostDataInteractionAccess(mutableMapOf(), HostDataInteractionStore(), presentations)
        val reply = ElementInteractionResult(fieldConfigPatches = mapOf("sel" to mapOf("search_placeholder" to "Near {{responses.places.home.city}}")))
        val call: ElementInteractionCall = { _, _, _, _, _ -> reply }
        val p1 = presentations.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = false)
        var wiring by mutableStateOf(StepHostDataWiring(access, p1, call) { true })
        val answers = home(google)
        seedStore(google)
        show {
            androidx.compose.runtime.CompositionLocalProvider(LocalStepHostDataWiring provides wiring) {
                OnboardingStepView(step = step, effectiveConfig = step.config, onNext = {}, onSkip = {}, accumulatedResponses = answers)
            }
        }
        compose.waitForIdle()
        assertFalse("control: nothing on the step templates a Google answer yet", creditShown())
        compose.onNodeWithText("More").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("the patch templates a Google answer: the credit", creditShown())

        presentations.presentationFor(other, 1, hasDelegate = true, hasCachedOverride = false)
        val p2 = presentations.presentationFor(step, 0, hasDelegate = true, hasCachedOverride = false)
        wiring = StepHostDataWiring(access, p2, call) { true }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertFalse("the new presentation has no patch: no credit", creditShown())
    }

    /**
     * Typing into the step does not rebuild the step's credit sources. The Google answer is THIS step's
     * own (a location field on the step answered from Google), so the decision reads the step's live
     * inputs and re-runs on every keystroke into any field; through the memo a keystroke into a field
     * holding no Google answer re-reads only where the Google answers sit. RED CONTROL: the decision
     * re-run straight through `GoogleCredit.shows` builds the sources once per keystroke.
     */
    @Test
    fun aKeystrokeDoesNotRebuildTheCreditSources() {
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_type", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf("id" to "t", "type" to "text", "text" to "Near {{step.home.city}}"),
                        mapOf("id" to "nick", "type" to "input_text", "field_id" to "nick"),
                    ),
                ),
            ),
        )!!
        show {
            OnboardingStepView(
                step = step, effectiveConfig = step.config, onNext = {}, onSkip = {},
                savedResponses = mapOf("home" to google),
            )
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue("control: the step credits", creditShown())
        val start = GoogleCredit.sourcesBuilt
        val field = compose.onAllNodes(hasSetTextAction())[0]
        for (c in listOf("a", "b", "c", "d")) {
            field.performTextInput(c)
            compose.waitForIdle()
        }
        assertEquals("four keystrokes, no rebuild of the step's sources", 0, GoogleCredit.sourcesBuilt - start)
        assertTrue("the credit is still drawn", creditShown())
    }

    // ---- What is behind a field ------------------------------------------------------------------

    /** The variant each Google logo on screen draws, sheets included. */
    private fun logoVariants(): List<GoogleMapsAttribution.Variant> =
        compose.onAllNodes(hasTestTag(GoogleMapsAttribution.LOGO_TAG)).fetchSemanticsNodes().map { it.config[GoogleLogoVariantKey] }

    /**
     * A field inside an option's sheet draws its logo on the SHEET, not on the step under it. The step
     * is dark; the user opens the sheet, types into its location field and taps a Google suggestion.
     * The sheet is filled with its light container colour, so the logo beside the field is the GRAY
     * variant. RED CONTROL: a sheet that provides no backdrop of its own hands the dark step's down,
     * and the WHITE logo is drawn on the light sheet.
     */
    @Test
    fun aFieldInASheetPicksItsLogoFromTheSheet() {
        locationGeocodeTransport = Stub("google")
        val step = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_sheet", "type" to "custom",
                "config" to mapOf(
                    "background" to mapOf("type" to "color", "color" to "#0F172A"),
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "sel", "type" to "input_select", "field_id" to "pick",
                            "field_config" to mapOf("display_style" to "list"),
                            "field_options" to listOf(
                                mapOf(
                                    "id" to "trip", "label" to "Plan a trip",
                                    "sheet_blocks" to listOf(
                                        mapOf("id" to "where", "type" to "input_location", "field_id" to "where",
                                            "field_config" to mapOf("location_min_chars" to 3)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )!!
        renderStep(step, mapbox)
        compose.onNodeWithText("Plan a trip").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        typeAndList()
        tapTheRow()
        assertEquals("the field in the sheet keeps Google's logo, on the sheet's light fill",
            listOf(GoogleMapsAttribution.Variant.GRAY), logoVariants())
    }

    /**
     * A field inside a filled container draws its logo on the container's fill, not on the step's. The
     * step is dark and the stack is white. RED CONTROL: a block that provides nothing of its own hands the
     * dark step's backdrop down, and the WHITE logo is drawn on the white stack.
     */
    @Test
    fun aFieldInAFilledContainerPicksItsLogoFromTheFill() {
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s_stack", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "st", "type" to "stack", "height" to 160,
                            "block_style" to mapOf("background_color" to "#FFFFFF"),
                            "children" to listOf(mapOf("id" to "loc", "type" to "input_location", "field_id" to "home")),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        val inputValues = mutableStateMapOf<String, Any>("home" to google)
        show {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain("#0F172A"),
            ) {
                ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
            }
        }
        compose.waitForIdle()
        assertEquals("the field in the white stack draws the gray logo",
            listOf(GoogleMapsAttribution.Variant.GRAY), logoVariants())
    }

    // ---- Frames ----------------------------------------------------------------------------------

    /**
     * The rule allows one `setContent` per test, and several cases render twice (Google, then Mapbox).
     * The content is swapped instead, under a new `key`, so each render is a fresh composition.
     */
    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var generation by mutableIntStateOf(0)
    private var contentSet = false

    private fun show(content: @Composable () -> Unit) {
        shown = content
        generation += 1
        if (!contentSet) {
            contentSet = true
            compose.setContent { key(generation) { shown?.invoke() } }
        }
        compose.waitForIdle()
    }

    private var frameSeq = 0

    private fun capture(name: String): android.graphics.Bitmap {
        val path = "build/tmp/google-attribution/${frameSeq++}-$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val f = File(path)
        check(f.exists()) { "no frame was written to $path — every comparison would be about a missing file" }
        return android.graphics.BitmapFactory.decodeFile(path)
    }

    private class Diff(val count: Int, val top: Int)

    /** How many pixels differ between two frames, and the first row (top-down) where one does. */
    private fun diff(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Diff {
        assertEquals("frames are the same size", a.width to a.height, b.width to b.height)
        var count = 0
        var top = Int.MAX_VALUE
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) {
                count += 1
                if (y < top) top = y
            }
        }
        return Diff(count, top)
    }
}
