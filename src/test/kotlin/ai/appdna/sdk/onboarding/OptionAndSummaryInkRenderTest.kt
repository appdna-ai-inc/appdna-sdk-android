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
 * RENDERED, through the real `ContentBlockRendererView`:
 * - unstyled `input_select` option labels on a dark step must be light (near-white pixels appear);
 * - an unstyled `summary_screen` headline on a cream step must be dark (near-black pixels appear).
 * THE CONTROLS: the same block with the colour authored light / dark draws the expected pixels, and with it
 * authored the other way draws none, so the harness both sees the ink and does not always see it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class OptionAndSummaryInkRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/option-summary-ink/$tag.png"
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

    private val nearWhite = { r: Int, g: Int, b: Int -> r > 200 && g > 200 && b > 200 }
    private val nearBlack = { r: Int, g: Int, b: Int -> r < 60 && g < 60 && b < 60 }

    private fun select(color: String?): ContentBlock = block(buildMap {
        put("id", "sel"); put("type", "input_select"); put("field_id", "goal")
        put("field_options", listOf(mapOf("value" to "a", "label" to "WWWWWWWWWW"), mapOf("value" to "b", "label" to "WWWWWWWWWW")))
        put("field_config", buildMap<String, Any> { put("display_style", "stacked"); if (color != null) put("text_color", color) })
    })

    private fun summary(color: String?): ContentBlock = block(buildMap {
        put("id", "sum"); put("type", "summary_screen"); put("text", "WWWWWWWW WWWW")
        if (color != null) put("text_color", color)
    })

    @Test
    fun `unstyled option labels on a dark step are light`() {
        val dark = count(select("#111111"), "#0B1220", "sel-dark") { r, g, b -> nearWhite(r, g, b) }
        val white = count(select("#FFFFFF"), "#0B1220", "sel-white") { r, g, b -> nearWhite(r, g, b) }
        val unstyled = count(select(null), "#0B1220", "sel-unstyled") { r, g, b -> nearWhite(r, g, b) }
        println("[option-ink] dark=$dark white=$white unstyled=$unstyled")
        assertTrue("harness control: $white vs $dark", white > dark + 100)
        assertTrue("unstyled option labels on a dark step are not light: $unstyled vs $dark", unstyled > dark + 100)
    }

    @Test
    fun `an unstyled summary headline on a cream step is dark`() {
        val white = count(summary("#FFFFFF"), "#FFF8EC", "sum-white") { r, g, b -> nearBlack(r, g, b) }
        val black = count(summary("#111111"), "#FFF8EC", "sum-black") { r, g, b -> nearBlack(r, g, b) }
        val unstyled = count(summary(null), "#FFF8EC", "sum-unstyled") { r, g, b -> nearBlack(r, g, b) }
        println("[summary-ink] white=$white black=$black unstyled=$unstyled")
        assertTrue("harness control: $black vs $white", black > white + 100)
        assertTrue("unstyled summary headline on cream is not dark: $unstyled vs $white", unstyled > white + 100)
    }
}
