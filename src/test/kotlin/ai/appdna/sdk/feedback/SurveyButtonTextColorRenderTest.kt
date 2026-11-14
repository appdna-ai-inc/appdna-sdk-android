package ai.appdna.sdk.feedback

import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1823 — RENDERED: a survey's Next and Submit labels draw in `theme.button_text_color`, as on iOS
 * (`SurveyRenderer` `.foregroundColor(buttonTextColor)`). Android hard-coded white, so a generated survey whose brand
 * button is light (dark label chosen for contrast) drew white-on-coral at 3.6:1.
 *
 * Read off the pixels inside the button: the box of button-coloured pixels on the right half (where Next and Submit sit),
 * and the dark and white ink inside it.
 * THE CONTROL: the same survey with `button_text_color` #FFFFFF draws white ink there and no dark ink, so the reader can
 * tell the two apart. Synthetic survey.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurveyButtonTextColorRenderTest {
    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private data class Ink(val dark: Int, val white: Int, val button: Int)

    private fun render(questions: Int, label: String): Ink {
        val config = SurveyConfig.fromMap(
            mapOf(
                "name" to "Probe", "survey_type" to "custom",
                "questions" to (1..questions).map { mapOf("id" to "q$it", "type" to "free_text", "text" to "Question $it", "required" to false) },
                "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
                "appearance" to mapOf(
                    "presentation" to "fullscreen", "dismiss_allowed" to false, "show_progress" to false,
                    "theme" to mapOf("button_color" to "#E4572E", "accent_color" to "#E4572E", "button_text_color" to label),
                ),
            ),
        )!!
        val path = "build/tmp/survey-button-ink/$questions-${label.drop(1)}.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            SurveyScreen(config = config, onComplete = {}, onDismiss = {}, isDark = false)
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        fun isButton(c: Int) = kotlin.math.abs(android.graphics.Color.red(c) - 0xE4) < 12 &&
            kotlin.math.abs(android.graphics.Color.green(c) - 0x57) < 12 && kotlin.math.abs(android.graphics.Color.blue(c) - 0x2E) < 12
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var button = 0
        // The right half only: the Back link on the left is drawn in the accent (the same coral) and would stretch the box
        // over the page's white background.
        for (y in 0 until bmp.height) for (x in bmp.width / 2 until bmp.width) if (isButton(bmp.getPixel(x, y))) {
            button++; minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        var dark = 0; var white = 0
        // Inset by half the height: a pill's rounded ends leave the page's white in the box's corners.
        val inset = (maxY - minY) / 2
        if (maxX >= 0) for (y in minY..maxY) for (x in (minX + inset)..(maxX - inset)) {
            val c = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (r < 70 && g < 70 && b < 70) dark++
            if (r > 235 && g > 235 && b > 235) white++
        }
        return Ink(dark, white, button)
    }

    @Test
    fun `next and submit labels take the theme's button text colour`() {
        val next = render(2, "#111827")
        val nextControl = render(2, "#FFFFFF")
        val submit = render(1, "#111827")
        val submitControl = render(1, "#FFFFFF")
        println("[survey-button-ink] next=$next nextControl=$nextControl submit=$submit submitControl=$submitControl")
        for ((name, ink) in listOf("next" to next, "nextControl" to nextControl, "submit" to submit, "submitControl" to submitControl)) {
            assertTrue("$name: no button was drawn in the button colour: $ink", ink.button > 500)
        }
        assertTrue("harness control (Next): a white label reads as white ink: $nextControl", nextControl.white > 30 && nextControl.dark < 5)
        assertTrue("harness control (Submit): a white label reads as white ink: $submitControl", submitControl.white > 30 && submitControl.dark < 5)
        assertTrue("Next ignored button_text_color #111827: $next", next.dark > 30 && next.white < 5)
        assertTrue("Submit ignored button_text_color #111827: $submit", submit.dark > 30 && submit.white < 5)
    }
}
