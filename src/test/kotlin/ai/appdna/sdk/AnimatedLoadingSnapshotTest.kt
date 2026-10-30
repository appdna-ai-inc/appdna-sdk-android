package ai.appdna.sdk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two `animated_loading` variants whose animation NEVER SETTLES.
 *
 * 🔴 These are the two snapshots that OOMed while recording, on every machine and at
 * every heap from 1g to 6g, and they are the reason `verifyRoborazziDebug` has never been wired
 * into CI. Raising the heap did not fix it (6g got the 7 GB runner reclaimed instead) and neither
 * did `forkEvery`, because it is not accumulation across the suite: filtering the run down to
 * five classes still OOMed on exactly these two.
 *
 * The stack says what it actually is:
 *
 *     java.lang.OutOfMemoryError: Java heap space
 *       at java.awt.image.DataBufferInt.<init>
 *       at com.github.takahirom.roborazzi.BitmapToBufferedImageConverter.bitmapToImage
 *       at com.github.takahirom.roborazzi.AwtRoboCanvas$drawImage$1.invoke
 *
 * A capture that waits for idle, against a `rememberInfiniteTransition` that is never idle, keeps
 * taking frames — and the canvas it composes them onto grows until a single `BufferedImage`
 * cannot be allocated. `circular` spins the same way and does not OOM because its angle drives a
 * `graphicsLayer` on a `CircularProgressIndicator` rather than invalidating a `Canvas` draw.
 *
 * 🔴 And moving the rotation out of the layer and into the draw did NOT help — measured, a full
 * record pass either way: `tests 42 failures 2`, both `OutOfMemoryError`. The fix is not in the
 * product code; it is that the capture has to name a MOMENT.
 *
 * So the clock is paused and advanced to a fixed point before the frame is taken. That makes the
 * capture bounded AND deterministic — the previous goldens recorded whatever frame the animation
 * happened to be on, which is its own reason for a golden to flap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AnimatedLoadingSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A quarter of the way through the cog's 2000 ms turn, and a third of the way through the
     * splash's 900 ms one: far enough from 0 that a frozen frame still shows the rotation is
     * applied, and a round number so the golden is reproducible by reading this line.
     */
    private fun captureAt(advanceMs: Long, path: String, block: Map<String, Any>) {
        val step = mapOf<String, Any>(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>("content_blocks" to listOf(block)),
        )
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()

        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1117))
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ContentBlockRendererView(
                        blocks = blocks,
                        onAction = {},
                        toggleValues = mutableMapOf(),
                        inputValues = mutableMapOf(),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.onRoot().captureRoboImage(path)
    }

    @Test
    fun loadingCogSpinner() {
        captureAt(
            advanceMs = 500,
            path = "src/test/snapshots/loading_cog.png",
            block = mapOf(
                "id" to "ld2", "type" to "animated_loading",
                "loading_variant" to "cog", "progress_color" to "#6366F1",
            ),
        )
    }

    @Test
    fun loadingSplashBottom() {
        captureAt(
            advanceMs = 300,
            path = "src/test/snapshots/loading_splash.png",
            block = mapOf(
                "id" to "ld3", "type" to "animated_loading",
                "loading_variant" to "splash_bottom", "height" to 360, "progress_color" to "#6366F1",
            ),
        )
    }
}
