package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The rest of an onboarding step's unstyled ink follows the step behind it, as text and labels do: the
 * typed text of an unstyled text input, its placeholder, and the step chrome (the ✕, and the Skip link of a
 * legacy step; a block-based step draws no step-level Skip).
 * Dark on a light step, as before; light on a dark, gradient or image step. Authored colours win.
 *
 * The onboarding activity is real (Robolectric, native graphics). Text is typed into the field through
 * its own semantics action; light pixels are counted inside each element's bounds, the field's inset so
 * its own outline is left out. The controls are the white step (dark ink) and an authored colour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UnstyledInkChromeAndInputsTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        System.setProperty("roborazzi.test.record", "true")
        events = BillingTestPipeline(ctx, "unstyled_ink_chrome_inputs")
    }

    @After
    fun tearDown() {
        for (c in controllers.asReversed()) {
            if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() }
        }
        controllers.clear()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun step(background: Map<String, Any>?, authored: Boolean): Map<String, Any> {
        val input = mutableMapOf<String, Any>("id" to "b_in", "type" to "input_text", "field_id" to "name")
        if (authored) input["field_style"] = mapOf("text_color" to "#FF0000")
        val config = mutableMapOf<String, Any>("content_blocks" to listOf(input))
        if (background != null) config["background"] = background
        return mapOf("id" to "s_ink", "type" to "custom", "config" to config)
    }

    /** The step on screen with "WWWWWW" typed; the frames of the typed field (inset) and the ✕. */
    private fun show(background: Map<String, Any>?, authored: Boolean = false, name: String): Map<String, Frame> {
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Ink probe", "version" to 1,
                "settings" to mapOf("dismiss_allowed" to true),
                "steps" to listOf(
                    step(background, authored),
                    mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End")),
                ),
            )))).toString(),
        )
        AppDNA.installOnboardingForTest(
            OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker),
            ready = true,
        )
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle()
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("WWWWWW")
        compose.waitForIdle()
        val inset = 24f
        val fb = field.fetchSemanticsNode().boundsInRoot
        val frames = mutableMapOf(
            "typed text" to Rect(fb.left + inset, fb.top + inset, fb.right - inset, fb.bottom - inset),
            "close" to compose.onAllNodes(hasContentDescription("Close onboarding"))[0].fetchSemanticsNode().boundsInRoot,
        )
        val path = "build/tmp/unstyled-ink-chrome/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val bitmap = BitmapFactory.decodeFile(path) ?: error("no frame was written to $path")
        c.pause().stop().destroy()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        return frames.mapValues { (_, b) -> Frame(bitmap, b) }
    }

    class Frame(val bitmap: Bitmap, val bounds: Rect) {
        private fun pixels(): Sequence<Int> = sequence {
            val l = bounds.left.toInt().coerceAtLeast(0)
            val t = bounds.top.toInt().coerceAtLeast(0)
            val r = bounds.right.toInt().coerceAtMost(bitmap.width)
            val b = bounds.bottom.toInt().coerceAtMost(bitmap.height)
            for (y in t until b) for (x in l until r) yield(bitmap.getPixel(x, y))
        }
        fun lightInk(): Int = pixels().count { luminance(it) > 0.6 }
        fun darkInk(): Int = pixels().count { luminance(it) < 0.6 }
        fun redInk(): Int = pixels().count { ((it shr 16) and 0xFF) > 200 && ((it shr 8) and 0xFF) < 80 && (it and 0xFF) < 80 }
        private fun luminance(c: Int): Double =
            (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
    }

    private val white = mapOf("type" to "color", "color" to "#FFFFFF")
    private val navy = mapOf("type" to "color", "color" to "#0F172A")
    private val gradient = mapOf(
        "type" to "gradient",
        "gradient" to mapOf(
            "type" to "linear", "angle" to 180,
            "stops" to listOf(mapOf("color" to "#1E1B4B", "position" to 0), mapOf("color" to "#312E81", "position" to 1)),
        ),
    )

    private val ink = 20

    @Test
    fun `on a white step the typed text, the close glyph and Skip are dark, as before`() {
        val counts = show(white, name = "white").mapValues { it.value.darkInk() }
        assertTrue("dark ink on white, per element: $counts", counts.values.all { it > ink })
    }

    @Test
    fun `on a dark step the typed text, the close glyph and Skip are light`() {
        val counts = show(navy, name = "navy").mapValues { it.value.lightInk() }
        assertTrue("light ink on navy, per element: $counts", counts.values.all { it > ink })
    }

    @Test
    fun `on a gradient step the typed text, the close glyph and Skip are light`() {
        val counts = show(gradient, name = "gradient").mapValues { it.value.lightInk() }
        assertTrue("light ink on the gradient, per element: $counts", counts.values.all { it > ink })
    }

    /** A legacy (welcome) step with Skip: the frame of its Skip link. */
    private fun skipFrame(background: Map<String, Any>, name: String): Frame {
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Ink probe", "version" to 1,
                "steps" to listOf(
                    mapOf("id" to "s_w", "type" to "welcome", "config" to mapOf("title" to "Hello", "skip_enabled" to true, "background" to background)),
                    mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End")),
                ),
            )))).toString(),
        )
        AppDNA.installOnboardingForTest(
            OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker),
            ready = true,
        )
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle()
        compose.waitForIdle()
        val bounds = compose.onNode(hasText("Skip"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val path = "build/tmp/unstyled-ink-chrome/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val bitmap = BitmapFactory.decodeFile(path) ?: error("no frame was written to $path")
        c.pause().stop().destroy()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        return Frame(bitmap, bounds)
    }

    @Test
    fun `a legacy step's Skip is light on a dark step and dark on a white one`() {
        val navySkip = skipFrame(navy, "skip-navy")
        assertTrue("Skip on navy (${navySkip.lightInk()} light pixels)", navySkip.lightInk() > ink)
        val whiteSkip = skipFrame(white, "skip-white")
        assertTrue("Skip on white (${whiteSkip.darkInk()} dark pixels)", whiteSkip.darkInk() > ink)
    }

    @Test
    fun `an authored text colour still wins on a dark step`() {
        val f = show(navy, authored = true, name = "navy-authored").getValue("typed text")
        assertTrue("the authored red typed text is drawn (${f.redInk()})", f.redInk() > ink)
    }

    private companion object {
        const val FLOW = "ink_chrome_probe"
    }
}
