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
import androidx.compose.ui.test.performTextInput
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
 * An `input_password` block with no field style is visible on the step it sits on: grey outline and
 * dark text on a light step, white outline and white text on a dark, gradient or image step. The
 * light or dark decision is the one the Google logo makes, from what the step host draws behind the
 * block ([GoogleMapsAttribution.backdrop]).
 *
 * The onboarding activity is real (Robolectric, native graphics), so the step background is the one
 * the host draws. Each case types a password, captures the screen, and counts the pixels inside the
 * field's bounds that differ from the step's own colour. Every case carries its control: an authored
 * field on the same step, which the harness must see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PasswordFieldDefaultColorsTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        System.setProperty("roborazzi.test.record", "true")
        events = BillingTestPipeline(ctx, "password_field_default_colors")
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

    private fun step(background: Map<String, Any>?, fieldStyle: Map<String, Any>?): Map<String, Any> {
        val block = mutableMapOf<String, Any>("id" to "b_pw", "type" to "input_password", "field_id" to "pw")
        if (fieldStyle != null) block["field_style"] = fieldStyle
        val config = mutableMapOf<String, Any>("content_blocks" to listOf(block))
        if (background != null) config["background"] = background
        return mapOf("id" to "s_pw", "type" to "custom", "config" to config)
    }

    /** The step on screen with a typed password; what the screen shows inside the field's bounds. */
    private fun show(background: Map<String, Any>?, fieldStyle: Map<String, Any>? = null, name: String): Frame {
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Password colour probe", "version" to 1,
                "steps" to listOf(
                    step(background, fieldStyle),
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
        field.performTextInput("hunter22")
        compose.waitForIdle()
        val bounds = field.fetchSemanticsNode().boundsInRoot
        val path = "build/tmp/password-default-colors/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val bitmap = BitmapFactory.decodeFile(path) ?: error("no frame was written to $path")
        // Close this flow so the next case presents a fresh one.
        c.pause().stop().destroy()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        return Frame(bitmap, bounds)
    }

    class Frame(val bitmap: Bitmap, val bounds: Rect) {
        private fun pixels(): Sequence<Int> = sequence {
            val l = bounds.left.toInt().coerceAtLeast(0)
            val t = bounds.top.toInt().coerceAtLeast(0)
            val r = bounds.right.toInt().coerceAtMost(bitmap.width)
            val b = bounds.bottom.toInt().coerceAtMost(bitmap.height)
            for (y in t until b) for (x in l until r) yield(bitmap.getPixel(x, y))
        }

        /** Pixels in the field that differ from [hex] by more than a faint anti-aliasing step. */
        fun inkAgainst(hex: Int): Int = pixels().count { distance(it, hex) > 40 }

        /** Pixels in the field that are light: white or near it. */
        fun lightInk(): Int = pixels().count { luminance(it) > 0.6 }

        /** Pixels in the field that are dark: what dark text draws. */
        fun darkInk(): Int = pixels().count { luminance(it) < 0.35 }

        private fun distance(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
        }

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
    private val authored = mapOf("border_color" to "#374151", "text_color" to "#111827")

    /** Enough pixels to be an outline and eight dots, far more than anti-aliasing leaves behind. */
    private val visible = 300

    @Test
    fun `an unstyled password field shows on a white step`() {
        val control = show(white, authored, "white-authored")
        assertTrue("control: the harness sees an authored field on white (${control.inkAgainst(WHITE)})",
            control.inkAgainst(WHITE) > visible)

        val unstyled = show(white, name = "white-unstyled")
        val ink = unstyled.inkAgainst(WHITE)
        assertTrue("an unstyled password field on a white step must draw something visible; it drew $ink pixels",
            ink > visible)
        assertTrue("its typed text is dark on white (${unstyled.darkInk()} dark pixels)", unstyled.darkInk() > 40)
    }

    @Test
    fun `an unstyled password field shows on a step with no background, in a light theme`() {
        val unstyled = show(background = null, name = "theme-unstyled")
        val ink = unstyled.inkAgainst(WHITE)
        assertTrue("the light theme's background is white; the field must show on it, it drew $ink pixels",
            ink > visible)
    }

    @Test
    fun `an unstyled password field stays white on a dark step`() {
        val unstyled = show(navy, name = "navy-unstyled")
        assertTrue("on a dark step the unstyled field draws white (${unstyled.lightInk()} light pixels)",
            unstyled.lightInk() > 40)
        assertTrue("the field shows against the navy step", unstyled.inkAgainst(NAVY) > visible)
    }

    @Test
    fun `an unstyled password field stays white on a gradient step`() {
        val unstyled = show(gradient, name = "gradient-unstyled")
        assertTrue("on a gradient step the unstyled field draws white (${unstyled.lightInk()} light pixels)",
            unstyled.lightInk() > 40)
    }

    @Test
    fun `an authored field style still wins on a white step`() {
        val red = show(white, mapOf("border_color" to "#FF0000", "text_color" to "#FF0000"), "white-red")
        val reds = red.bitmap.let { b ->
            var n = 0
            val l = red.bounds.left.toInt().coerceAtLeast(0); val r = red.bounds.right.toInt().coerceAtMost(b.width)
            val t = red.bounds.top.toInt().coerceAtLeast(0); val bt = red.bounds.bottom.toInt().coerceAtMost(b.height)
            for (y in t until bt) for (x in l until r) {
                val p = b.getPixel(x, y)
                if (((p shr 16) and 0xFF) > 200 && ((p shr 8) and 0xFF) < 80 && (p and 0xFF) < 80) n++
            }
            n
        }
        assertTrue("the authored red outline and text are drawn ($reds red pixels)", reds > visible)
    }

    private companion object {
        const val FLOW = "password_colour_probe"
        const val WHITE = 0xFFFFFF
        const val NAVY = 0x0F172A
    }
}
