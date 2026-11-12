package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A multi-buttons background reaching past the group, side by side, read off the PIXELS the real
 * renderer draws: "edge" on the bottom has to paint all the way to the bottom of the surface, "edge"
 * left to the left edge, and a set amount exactly that far.
 *
 * Every assertion has a control in the same test: the same group with nothing authored leaves those
 * pixels white, so a harness that cannot see the background fails rather than passes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class BlockBackgroundBleedRenderTest {
    @get:Rule val compose = createComposeRule()

    private val fill = 0xFFFF00FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun group(fc: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "content_blocks" to listOf(
                    mapOf(
                        "id" to "group", "type" to "multi_buttons", "field_config" to fc,
                        "block_style" to mapOf("background_color" to "#FF00FF"),
                        "stack_children" to listOf(
                            mapOf("id" to "b1", "type" to "button", "text" to "One", "action" to "next"),
                            mapOf("id" to "b2", "type" to "button", "text" to "Two", "action" to "next"),
                        ),
                    ),
                ),
            ),
        )?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** The group sits 32dp in from each side, 120dp above the bottom of a white surface. */
    private fun draw(fc: Map<String, Any>): android.graphics.Bitmap {
        val b = group(fc)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                        ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                    }
                    Spacer(Modifier.height(120.dp))
                }
            }
        }
        compose.waitForIdle()
        val path = "build/bleed-render/${System.nanoTime()}.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
    }

    private fun dp(v: Int) = (v * 2) // xhdpi

    @Test
    fun bottomEdgeAndSidesReachTheSurfaceEdges() {
        val bmp = draw(mapOf("background_bleed_bottom" to "edge", "background_bleed_left" to "edge", "background_bleed_right" to "edge"))
        val h = bmp.height
        // The very bottom of the surface, 120dp below the group.
        assertEquals("bottom edge painted", fill, bmp.getPixel(bmp.width / 2, h - 3))
        // Left of the 32dp side padding: the step inset (31.2dp at 390dp) is cancelled.
        assertEquals("left edge painted", fill, bmp.getPixel(dp(4), h - dp(60)))
        assertEquals("right edge painted", fill, bmp.getPixel(bmp.width - dp(4), h - dp(60)))
        // Above the group is still the surface.
        assertEquals("nothing painted above", white, bmp.getPixel(bmp.width / 2, dp(20)))
    }

    @Test
    fun controlNothingAuthoredPaintsNothingOutside() {
        val bmp = draw(emptyMap())
        val h = bmp.height
        assertEquals(white, bmp.getPixel(bmp.width / 2, h - 3))
        assertEquals(white, bmp.getPixel(dp(4), h - dp(130)))
        // …and the harness CAN see the group's own fill (otherwise "white" above proves nothing).
        val anyFill = (0 until bmp.height).any { y -> bmp.getPixel(bmp.width / 2, y) == fill || bmp.getPixel(dp(40), y) == fill }
        assertEquals("the harness sees the background", true, anyFill)
    }

    @Test
    fun aSetAmountReachesExactlyThatFar() {
        val bmp = draw(mapOf("background_bleed_bottom" to 40))
        val h = bmp.height
        // 120dp of white below the slot: 40 of it painted, the rest not.
        assertEquals("inside the 40dp", fill, bmp.getPixel(bmp.width / 2, h - dp(120) + dp(30)))
        assertNotEquals("past the 40dp", fill, bmp.getPixel(bmp.width / 2, h - dp(120) + dp(50)))
    }
}
