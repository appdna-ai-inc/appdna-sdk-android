package ai.appdna.sdk.messages

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.RoborazziOptions
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

/** An in-app message's unset text takes its ink from the message's own background, not the phone's appearance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SurfaceInkMessageRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun message(background: String, button: String) = MessageConfigParser.parseSingleMessage(mapOf(
        "name" to "n", "message_type" to "fullscreen", "trigger_rules" to mapOf("event" to "e"),
        "content" to mapOf("title" to "WWWWWWWWWW WWWWW", "body" to "WWWWWWWWWW WWWWW", "background_color" to background, "button_color" to button, "cta_text" to "OK"),
    ))!!

    // The button is coloured so neither count sees it, leaving only the text measured.
    private fun pixels(background: String, button: String, isDark: Boolean, tag: String, match: (Int) -> Boolean): Int {
        compose.setContent { InAppMessageView(config = message(background, button), onCTATap = {}, onDismiss = {}, isDark = isDark) }
        compose.waitForIdle()
        val path = "build/tmp/surface-ink/message-$tag.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (match(bmp.getPixel(x, y))) n++
        return n
    }

    private fun lum(c: Int) = (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0

    @Test
    fun `a white message in a dark phone draws dark text`() {
        val n = pixels("#FFFFFF", "#FFEB3B", true, "white-dark") { lum(it) < 0.5 }
        println("[surface-ink] message white-in-dark=$n")
        assertTrue("dark text on a white message in a dark phone: $n", n > 500)
    }

    @Test
    fun `a dark message in a light phone draws light text`() {
        val n = pixels("#0B1220", "#FF00FF", false, "dark-light") { lum(it) > 0.6 }
        println("[surface-ink] message dark-in-light=$n")
        assertTrue("light text on a dark message in a light phone: $n", n > 2000)
    }
}
