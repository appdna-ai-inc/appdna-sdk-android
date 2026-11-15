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
 * RENDERED: a survey's unstyled text takes its ink from the survey's authored background, not from the phone's
 * appearance. A white survey in a dark phone drew its Yes and No labels white on the light-grey buttons, because the
 * default text colour followed `isDark`.
 *
 * Oracle: the same survey with `text_color` #1A1A1A authored. Harness control: the unauthored survey with the phone
 * light draws that same ink. RED CONTROL: in `SurveyScreen`, fall back on `if (isDark) Color.White else …` again —
 * the dark-phone count drops to the question alone. iOS `SurfaceInkSurveyTests` is the same check. Synthetic survey.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurveySurfaceInkRenderTest {
    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun darkInk(textColor: String?, phoneDark: Boolean): Int {
        val theme = mutableMapOf<String, Any>("background_color" to "#FFFFFF")
        textColor?.let { theme["text_color"] = it }
        val config = SurveyConfig.fromMap(
            mapOf(
                "name" to "Probe", "survey_type" to "custom",
                "questions" to listOf(mapOf("id" to "q1", "type" to "yes_no", "text" to "Is this question readable", "required" to true)),
                "trigger_rules" to mapOf("event" to "app_open", "frequency" to "once"),
                "appearance" to mapOf("presentation" to "fullscreen", "dismiss_allowed" to false, "show_progress" to false, "theme" to theme),
            ),
        )!!
        val path = "build/tmp/survey-surface-ink/${textColor?.drop(1) ?: "none"}-${if (phoneDark) "dark" else "light"}.png"
        java.io.File(path).parentFile?.mkdirs()
        captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            SurveyScreen(config = config, onComplete = {}, onDismiss = {}, isDark = phoneDark)
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var dark = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) < 80 && android.graphics.Color.green(c) < 80 && android.graphics.Color.blue(c) < 80) dark++
        }
        return dark
    }

    @Test
    fun `a white survey in a dark phone draws dark text`() {
        val unauthored = darkInk(null, phoneDark = true)
        val oracle = darkInk("#1A1A1A", phoneDark = true)
        val lightPhone = darkInk(null, phoneDark = false)
        println("[surface-ink] android survey white-in-dark=$unauthored oracle=$oracle light-phone=$lightPhone")
        assertTrue("harness: the authored dark text is visible ($oracle)", oracle > 300)
        assertTrue("harness: a light phone draws the same dark text ($lightPhone vs $oracle)", lightPhone >= oracle * 95 / 100)
        assertTrue("the unauthored text on a white survey is dark in a dark phone ($unauthored vs $oracle)", unauthored >= oracle * 95 / 100)
    }
}
