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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
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
 * Unstyled text, headings and form-field labels are drawn in ink that shows on the step behind them:
 * dark on a light step (unchanged), white on a dark, gradient or image step. The light or dark call is
 * the one the Google logo makes, from what the step host draws behind the block
 * ([GoogleMapsAttribution.needsLightInk]). Authored colours always win.
 *
 * The onboarding activity is real (Robolectric, native graphics), so the step background is the one
 * the host draws. Each case captures the screen and counts light and dark pixels inside each element's
 * own bounds. The controls are the white step (dark ink, as before) and authored colours on a dark step.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UnstyledInkTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        System.setProperty("roborazzi.test.record", "true")
        events = BillingTestPipeline(ctx, "unstyled_ink")
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
        val heading = mutableMapOf<String, Any>("id" to "b_h", "type" to "heading", "text" to "Big heading", "level" to 1)
        val text = mutableMapOf<String, Any>("id" to "b_t", "type" to "text", "text" to "Some body text here")
        val input = mutableMapOf<String, Any>("id" to "b_in", "type" to "input_text", "field_id" to "name", "field_label" to "Your name label")
        if (authored) {
            heading["style"] = mapOf("color" to "#FF0000")
            text["style"] = mapOf("color" to "#FF0000")
            input["field_style"] = mapOf("label_color" to "#FF0000")
        }
        val config = mutableMapOf<String, Any>("content_blocks" to listOf(heading, text, input))
        if (background != null) config["background"] = background
        return mapOf("id" to "s_ink", "type" to "custom", "config" to config)
    }

    private val elements = listOf("Big heading", "Some body text here", "Your name label")

    /** The step on screen; each element's frame. */
    private fun show(background: Map<String, Any>?, authored: Boolean = false, name: String): Map<String, Frame> {
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Ink probe", "version" to 1,
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
        val bounds = elements.associateWith { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val path = "build/tmp/unstyled-ink/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val bitmap = BitmapFactory.decodeFile(path) ?: error("no frame was written to $path")
        c.pause().stop().destroy()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        return bounds.mapValues { (_, b) -> Frame(bitmap, b) }
    }

    class Frame(val bitmap: Bitmap, val bounds: Rect) {
        private fun pixels(): Sequence<Int> = sequence {
            val l = bounds.left.toInt().coerceAtLeast(0)
            val t = bounds.top.toInt().coerceAtLeast(0)
            val r = bounds.right.toInt().coerceAtMost(bitmap.width)
            val b = bounds.bottom.toInt().coerceAtMost(bitmap.height)
            for (y in t until b) for (x in l until r) yield(bitmap.getPixel(x, y))
        }
        fun lightInk(): Int = pixels().count { luminance(it) > 0.75 }
        fun darkInk(): Int = pixels().count { luminance(it) < 0.35 }
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

    /** Enough pixels to be glyphs, far more than anti-aliasing leaves. */
    private val ink = 40

    @Test
    fun `on a white step unstyled text, headings and labels are dark, as before`() {
        val frames = show(white, name = "white")
        for ((name, f) in frames) assertTrue("$name: dark ink on white (${f.darkInk()} dark pixels)", f.darkInk() > ink)
    }

    @Test
    fun `on a dark step unstyled text, headings and labels are light`() {
        val frames = show(navy, name = "navy")
        for ((name, f) in frames) assertTrue("$name: light ink on navy (${f.lightInk()} light pixels)", f.lightInk() > ink)
    }

    @Test
    fun `on a gradient step unstyled text, headings and labels are light`() {
        val frames = show(gradient, name = "gradient")
        for ((name, f) in frames) assertTrue("$name: light ink on the gradient (${f.lightInk()} light pixels)", f.lightInk() > ink)
    }

    @Test
    fun `authored colours still win on a dark step`() {
        val frames = show(navy, authored = true, name = "navy-authored")
        for ((name, f) in frames) assertTrue("$name: the authored red is drawn (${f.redInk()} red pixels)", f.redInk() > ink)
    }

    private companion object {
        const val FLOW = "ink_probe"
    }
}
