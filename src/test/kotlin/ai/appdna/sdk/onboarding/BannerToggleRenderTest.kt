package ai.appdna.sdk.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The warning banner's single background, drawn, and a toggle that reaches its host, tapped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BannerToggleRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun flowWith(vararg blocks: Map<String, Any>): OnboardingFlowConfig = OnboardingConfigParser.parseFlowForTest(
        "f",
        mapOf("id" to "f", "name" to "f", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf(mapOf("id" to "s", "type" to "content", "content_blocks" to blocks.toList()))),
    )

    private val interactions = mutableListOf<Triple<String, String, String?>>()
    private val recorder = object : AppDNAOnboardingDelegate {
        override fun onOnboardingStarted(flowId: String) {}
        override fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {}
        override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {}
        override fun onOnboardingDismissed(flowId: String, atStep: Int) {}
        override suspend fun onElementInteraction(flowId: String, stepId: String, blockId: String, action: String, value: String?, inputValues: Map<String, Any>): ElementInteractionResult? {
            interactions += Triple(blockId, action, value)
            return null
        }
    }

    private fun host(flow: OnboardingFlowConfig, delegate: AppDNAOnboardingDelegate? = null) {
        compose.setContent {
            OnboardingFlowHost(
                flow = flow, viewModel = null, delegate = delegate,
                onStepViewed = { _, _ -> }, onStepCompleted = { _, _, _ -> }, onStepSkipped = { _, _ -> },
                onFlowCompleted = {}, onFlowDismissed = { _, _ -> },
            )
        }
        compose.waitForIdle()
    }

    /** Pixels exactly the authored colour, and the bounding box they occupy. */
    private data class Red(val count: Int, val minX: Int, val minY: Int, val isCorner: Boolean)
    private fun red(name: String): Red {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        val path = "build/tmp/banner-toggle/$name.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        val isRed = { c: Int -> android.graphics.Color.red(c) > 250 && android.graphics.Color.green(c) < 5 && android.graphics.Color.blue(c) < 5 }
        var n = 0; var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (isRed(bmp.getPixel(x, y))) { n++; if (x < minX) minX = x; if (y < minY) minY = y }
        }
        return Red(n, minX, minY, n > 0 && isRed(bmp.getPixel(minX, minY)))
    }

    private fun banner(blockStyle: Map<String, Any>) = mapOf<String, Any>(
        "id" to "b", "type" to "warning_banner", "text" to "Check your inbox", "block_style" to blockStyle,
    )

    @Test
    fun `a Design panel background is the banner's one background`() {
        host(flowWith(banner(mapOf("background_color" to "#FF0000", "border_radius" to 24))))
        val r = red("design-background")
        // The old card painted the variant tint over this fill, so no pixel was the authored red.
        assertTrue("the authored background must be what shows, got ${r.count} pixels", r.count > 5000)
        assertTrue("the fill must follow the corner radius, not draw a square behind it", !r.isCorner)
    }

    @Test
    fun `the control - a banner's own background still covers block_style`() {
        host(flowWith(banner(mapOf("background_color" to "#00FF00", "border_radius" to 24)) + mapOf("bg_color" to "#FF0000")))
        assertTrue("bg_color is the banner's own setting and wins", red("own-background").count > 5000)
    }

    @Test
    fun `a toggle with an action tells the host and carries the new value`() {
        val toggle = mapOf<String, Any>(
            "id" to "theme", "type" to "toggle", "toggle_label" to "Dark mode",
            "field_config" to mapOf("toggle_action" to "toggle_dark_mode", "toggle_on_color" to "#FF0000"),
        )
        host(flowWith(toggle), recorder)
        compose.onNodeWithText("Dark mode").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("theme", "toggle_dark_mode", "true")), interactions)
    }

    @Test
    fun `the control - a toggle with no action does not call the host`() {
        val toggle = mapOf<String, Any>("id" to "t", "type" to "toggle", "toggle_label" to "Plain")
        host(flowWith(toggle), recorder)
        compose.onNodeWithText("Plain").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Triple<String, String, String?>>(), interactions)
    }
}
