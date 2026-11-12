package ai.appdna.sdk.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Animated Image block LOADS its image through the real Coil path and draws the strip — the half
 * [AnimatedImageStripRenderTest] cannot see, because it hands the strip a painter already in hand.
 *
 * 🔴 Red without the fix: a Coil request with no size takes it from where its painter is drawn, and
 * the block only drew the painter once it had loaded, so the request waited forever and the strip never
 * appeared. Verified by removing `.size(Size.ORIGINAL)`: this test times out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-mdpi")
class AnimatedImageCoilLoadTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theBlockLoadsItsImageAndDrawsTheStrip() {
        val png = File.createTempFile("long", ".png")
        Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF0000.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, png.outputStream())
        val block = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "content_blocks" to listOf(
                    mapOf(
                        "id" to "anim", "type" to "animated_image", "image_url" to png.toURI().toString(), "height" to 50,
                        "field_config" to mapOf("scroll_direction" to "left", "scroll_speed" to 40),
                    ),
                ),
            ),
        )?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

        // The strip's own frame loop never idles, so the clock is driven by hand: advance a frame, let the
        // image loader's IO finish and post back, look again.
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(200.dp, 50.dp)) { AnimatedImageBlock(block) } }
        var drawn = false
        for (i in 0 until 150) {
            compose.mainClock.advanceTimeByFrame()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
            // The UNMERGED tree: a block with no alt is decorative, so its node clears its children's semantics
            // (test tags included) from the tree TalkBack reads; the drawn strip is still there underneath.
            if (compose.onAllNodesWithTag("animated_image_strip", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { drawn = true; break }
        }
        org.junit.Assert.assertTrue("the image never loaded, so the strip never drew", drawn)
    }
}
