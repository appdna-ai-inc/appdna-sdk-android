package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.RoborazziOptions
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

/** A legacy (no `content_blocks`) step's secondary text takes its ink from the step behind it, not the phone's appearance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SurfaceInkLegacyStepRenderTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val controllers = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        System.setProperty("roborazzi.test.record", "true")
        events = BillingTestPipeline(ctx, "surface_ink_legacy")
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
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        events.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val subtitle = "WWWWWWWWWWWW"

    /** Pixels of the welcome step's subtitle matching [match]. */
    private fun subtitlePixels(bgHex: String, name: String, match: (Double) -> Boolean): Int {
        val flow = "legacy_ink"
        val step = mapOf("id" to "s_w", "type" to "welcome", "config" to mapOf(
            "title" to "Hello", "subtitle" to subtitle, "background" to mapOf("type" to "color", "color" to bgHex),
        ))
        LocalStorage(ctx).setString("cache_onboarding", JSONObject(mapOf("active_flow_id" to flow, "flows" to mapOf(flow to mapOf(
            "id" to flow, "name" to "Ink probe", "version" to 1,
            "steps" to listOf(step, mapOf("id" to "s_end", "type" to "welcome", "config" to mapOf("title" to "End"))),
        )))).toString())
        AppDNA.installOnboardingForTest(OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker), ready = true)
        val host = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
        assertTrue(AppDNA.presentOnboarding(host, flow))
        val c = Robolectric.buildActivity(OnboardingActivity::class.java).setup().also { controllers += it }
        idle()
        compose.waitForIdle()
        val b = compose.onNode(hasText(subtitle), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val path = "build/tmp/surface-ink/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        c.pause().stop().destroy()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        OnboardingActivity.clearActiveLaunches()
        var n = 0
        for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(bmp.height))
            for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(bmp.width)) {
                val p = bmp.getPixel(x, y)
                val l = (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
                if (match(l)) n++
            }
        return n
    }

    private val ink = 60

    @Test
    @Config(qualifiers = "+night")
    fun `on a white legacy step in a dark theme the subtitle is dark`() {
        val n = subtitlePixels("#FFFFFF", "legacy-white-night") { it < 0.6 }
        println("[surface-ink] legacy white-in-dark=$n")
        assertTrue("subtitle on white in a dark theme: $n dark pixels", n > ink)
    }

    @Test
    fun `on a dark legacy step in a light theme the subtitle is light`() {
        val n = subtitlePixels("#0B1220", "legacy-dark-light") { it > 0.45 }
        println("[surface-ink] legacy dark-in-light=$n")
        assertTrue("subtitle on a dark step in a light theme: $n light pixels", n > ink)
    }

    @Test
    fun `control - a white legacy step in a light theme is dark`() {
        val n = subtitlePixels("#FFFFFF", "legacy-white-light") { it < 0.6 }
        assertTrue("control: $n dark pixels", n > ink)
    }
}
