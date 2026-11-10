package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/** Timeline titles and subtitles take their ink from the surface they are drawn on, not from the phone's appearance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurfaceInkTimelineRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block on a [bgHex] step, under a [darkTheme] system theme. */
    private fun count(b: ContentBlock, bgHex: String, darkTheme: Boolean, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/surface-ink/$tag.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalGoogleAttributionBackdrop provides GoogleMapsAttribution.Backdrop.Plain(bgHex)) {
                    Box(Modifier.fillMaxWidth().background(Color(android.graphics.Color.parseColor(bgHex))).padding(16.dp)) {
                        ContentBlockRendererView(blocks = listOf(b), onAction = {}, toggleValues = mutableMapOf())
                    }
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
    private val nearBlack = { r: Int, g: Int, b: Int -> r < 90 && g < 90 && b < 90 }

    private val timeline: ContentBlock get() = block(mapOf(
        "id" to "tl", "type" to "timeline", "show_line" to false,
        // Dots in a colour neither count sees, so only the text is measured.
        "completed_color" to "#FF00FF", "current_color" to "#FF00FF", "upcoming_color" to "#FF00FF",
        "timeline_items" to listOf(
            mapOf("id" to "1", "title" to "WWWWWWWWWW", "subtitle" to "WWWWWWWWWW", "status" to "completed"),
            mapOf("id" to "2", "title" to "WWWWWWWWWW", "subtitle" to "WWWWWWWWWW", "status" to "current"),
            mapOf("id" to "3", "title" to "WWWWWWWWWW", "subtitle" to "WWWWWWWWWW", "status" to "upcoming"),
        ),
    ))

    private val darkish = { r: Int, g: Int, b: Int -> r < 140 && g < 140 && b < 140 }
    private val lightish = { r: Int, g: Int, b: Int -> r > 150 && g > 150 && b > 150 }

    // Measured against a fixed floor: on a dark step the titles were black in BOTH themes, so the matching theme is no control there.
    @Test
    fun `a timeline on a dark step is light ink in both themes`() {
        val inLight = count(timeline, "#0B1220", false, "timeline-dark-light") { r, g, b -> lightish(r, g, b) }
        val inDark = count(timeline, "#0B1220", true, "timeline-dark-dark") { r, g, b -> lightish(r, g, b) }
        println("[surface-ink] timeline dark-in-light=$inLight dark-in-dark=$inDark")
        assertTrue("timeline on a dark step: light theme $inLight, dark theme $inDark", inLight > 4000 && inDark > 4000)
    }

    // The control is the same timeline in the theme that matches its surface.
    @Test
    fun `a timeline on a white step in a dark theme is dark ink`() {
        val n = count(timeline, "#FFFFFF", true, "timeline-white-dark") { r, g, b -> darkish(r, g, b) }
        val control = count(timeline, "#FFFFFF", false, "timeline-white-light") { r, g, b -> darkish(r, g, b) }
        println("[surface-ink] timeline white-in-dark=$n control=$control")
        assertTrue("timeline on white in a dark theme: $n vs $control", control > 300 && n > control * 3 / 4)
    }
}
