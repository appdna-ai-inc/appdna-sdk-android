package ai.appdna.sdk.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A Screen section's unstyled text takes its ink from the section's own fill, else the screen's authored background —
 * not the phone's appearance. Drawn through the REAL `SectionRegistry.Render` (a `cta_footer` disclaimer, which reads
 * the theme's `onSurfaceVariant`) under the same `SurfaceTheme(backdrop(ofScreen))` the screen host sets, with the
 * phone's theme as the base, exactly as `ScreenHostActivity`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurfaceInkScreenRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() {
        System.setProperty("roborazzi.test.record", "true")
        SectionRegistry.registerBuiltInSections() // what AppDNA.configure does
    }

    private fun section(style: Map<String, Any?>?) = ScreenConfig.fromMap(mapOf(
        "id" to "scr_ink",
        "sections" to listOf(mapOf(
            "id" to "s1", "type" to "cta_footer",
            "data" to mapOf("disclaimer_text" to "WWWWWWWWWWWWWWWWWWWWWWWWWWWWW WWWWWWWWWWWWW"),
        ) + (style?.let { mapOf("style" to it) } ?: emptyMap())),
    )).sections.first()

    private fun lum(c: Int) = (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0

    private fun pixels(screenBg: String, sectionStyle: Map<String, Any?>?, phoneDark: Boolean, tag: String, match: (Int) -> Boolean): Int {
        val config = section(sectionStyle)
        val screenBackground = BackgroundConfig(type = "color", color = screenBg)
        compose.setContent {
            MaterialTheme(colorScheme = if (phoneDark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(screenBg)))) {
                    ai.appdna.sdk.onboarding.SurfaceTheme(behind = ai.appdna.sdk.onboarding.GoogleMapsAttribution.backdrop(ofScreen = screenBackground)) {
                        SectionRegistry.Render(config, SectionContext(screenId = "scr_ink", onAction = {}))
                    }
                }
            }
        }
        compose.waitForIdle()
        val path = "build/tmp/surface-ink/screen-$tag.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (match(bmp.getPixel(x, y))) n++
        return n
    }

    @Test
    fun `a white screen in a dark phone draws dark text`() {
        val n = pixels("#FFFFFF", null, phoneDark = true, tag = "white-dark") { lum(it) < 0.5 }
        println("[surface-ink] screen white-in-dark=$n")
        assertTrue("dark text on a white screen in a dark phone: $n", n > 300)
    }

    @Test
    fun `a dark screen in a light phone draws light text`() {
        val n = pixels("#0B1220", null, phoneDark = false, tag = "dark-light") { lum(it) > 0.6 }
        println("[surface-ink] screen dark-in-light=$n")
        assertTrue("light text on a dark screen in a light phone: $n", n > 300)
    }

    @Test
    fun `a white section on a dark screen draws dark text`() {
        // The screen (#0B1220, lum ~0.07) and the section (#FFFFFF) are both excluded by the band: only dark text drawn
        // on the white section lands between them. Light ink on the white section would land above it.
        val n = pixels("#0B1220", mapOf("background_color" to "#FFFFFF"), phoneDark = true, tag = "white-section") { lum(it) in 0.15..0.5 }
        println("[surface-ink] screen white-section-on-dark=$n")
        assertTrue("dark text in a white section on a dark screen: $n", n > 200)
    }
}
