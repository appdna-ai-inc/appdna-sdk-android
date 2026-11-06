package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED, through the real `ContentBlockRendererView`: an unstyled dropdown Select (the default display
 * style), single and multi, on a dark step draws its placeholder and chevron in light ink. THE CONTROLS: on a
 * white step it draws dark ink and no white; with an authored light field fill on the dark step, no white.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DropdownFieldInkRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/dropdown-ink/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
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

    private val white = { r: Int, g: Int, b: Int -> r > 225 && g > 225 && b > 225 }

    private fun select(multi: Boolean, fill: String? = null): ContentBlock = block(buildMap {
        put("id", "sel"); put("type", "input_select"); put("field_id", "goal"); put("field_placeholder", "WWWWWWWW WWWW")
        put("field_options", listOf(mapOf("value" to "a", "label" to "A")))
        if (multi) put("multi_select", true)
        if (fill != null) put("field_style", mapOf("background_color" to fill))
    })

    @Test
    fun `an unstyled dropdown on a dark step draws light ink`() {
        for (multi in listOf(false, true)) {
            val dark = count(select(multi), "#0B1220", "dark-$multi") { r, g, b -> white(r, g, b) }
            val filled = count(select(multi, "#F9FAFB"), "#0B1220", "filled-$multi") { r, g, b -> r in 240..250 && g in 245..252 && b in 248..255 }
            val filledInk = count(select(multi, "#F9FAFB"), "#0B1220", "filled-ink-$multi") { r, g, b -> r < 80 && g < 80 && b < 80 }
            println("[dropdown-ink multi=$multi] dark-step white=$dark filled=$filled filled-dark-ink=$filledInk")
            assertTrue("harness control: the light fill draws ($filled)", filled > 1000)
            assertTrue("a light-filled field keeps dark ink ($filledInk)", filledInk > 100)
            assertTrue("an unstyled dropdown on a dark step draws no light ink (multi=$multi): $dark", dark > 100)
        }
    }
}
