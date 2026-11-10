package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED, through the real `ContentBlockRendererView` on a white step: an authored translucent (8-digit) colour
 * keeps its alpha, and an opacity on top MULTIPLIES it, as on iOS —
 * - a `summary_screen` with `text_color` `#00000080` draws its default stat label at 0.5 × 0.7 = 0.35 (~#A6A6A6).
 * Pixels are counted near each expected composite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SummaryLabelAlphaRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/summary-label-alpha/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain(bgHex)) {
                Box(Modifier.fillMaxWidth().background(Color(android.graphics.Color.parseColor(bgHex))).padding(16.dp)) {
                    ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                }
            }
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (match(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n++
        }
        return n
    }

    private fun grey(v: Int, tol: Int = 6) = { r: Int, g: Int, b: Int -> kotlin.math.abs(r - v) <= tol && kotlin.math.abs(g - v) <= tol && kotlin.math.abs(b - v) <= tol }
    private val black = { r: Int, g: Int, b: Int -> r < 10 && g < 10 && b < 10 }

    private fun box(fill: String, opacity: Double?) = block(buildMap<String, Any> {
        put("id", "s"); put("type", "heading"); put("text", "WWWWWWWWWWWW"); put("element_width", "fill"); put("style", mapOf("font_size" to 40, "color" to "#FFFFFF"))
        put("field_config", buildMap<String, Any> { put("container_bg_color", fill); if (opacity != null) put("container_opacity", opacity) })
    })

    @Test
    fun `a translucent summary text colour keeps its alpha under the label alpha`() {
        fun summary(tc: String) = block(mapOf(
            "id" to "sum", "type" to "summary_screen", "text_color" to tc,
            "field_config" to mapOf("summary_stats" to listOf(mapOf("value" to "1", "label" to "WWWWWWWW", "color" to "#FF0000"))),
        ))
        // The stat card is #1F2937; the label is drawn over it. Expected composites over #1F2937 (31,41,55):
        // 0.35 black -> ~(20,27,36); 0.7 black (alpha replaced) -> ~(9,12,17).
        val near = { r0: Int, g0: Int, b0: Int -> { r: Int, g: Int, b: Int -> kotlin.math.abs(r - r0) <= 3 && kotlin.math.abs(g - g0) <= 3 && kotlin.math.abs(b - b0) <= 3 } }
        val at035 = count(summary("#00000080"), "#FFFFFF", "s-035") { r, g, b -> near(20, 27, 36)(r, g, b) }
        val at07 = count(summary("#00000080"), "#FFFFFF", "s-07") { r, g, b -> near(9, 12, 17)(r, g, b) }
        println("[translucent label] at035=$at035 at07=$at07")
        assertTrue("the label's 0.7 replaced the authored alpha (0.35: $at035, 0.7: $at07)", at035 > at07)
    }
}
