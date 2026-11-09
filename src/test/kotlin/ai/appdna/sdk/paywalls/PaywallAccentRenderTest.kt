package ai.appdna.sdk.paywalls

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

/** The trial badge has no colour field of its own, so it takes the paywall's accent: the CTA's authored colour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallAccentRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_accent",
        mapOf(
            "id" to "pw_accent", "name" to "Accent",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to listOf(mapOf(
                "id" to "sec_badge", "type" to "social_proof",
                "data" to mapOf("sub_type" to "trial_badge", "text" to "WWWWWWWW WWWW"),
            )),
            "cta" to mapOf("text" to "Continue", "bg_color" to "#FF00FF"),
        ),
    ) ?: error("paywall did not parse")

    @Test
    fun `the trial badge takes the paywall's accent`() {
        compose.setContent { PaywallScreen(config = paywall(), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.waitForIdle()
        val path = "build/tmp/paywall-accent/badge.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        // The upper part of the screen: the CTA, the other magenta thing, sits below it.
        var n = 0
        for (y in 0 until bmp.height * 55 / 100) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 180 && android.graphics.Color.green(c) < 110 && android.graphics.Color.blue(c) > 180) n++
        }
        println("[paywall-accent] magenta=$n")
        assertTrue("the badge text is drawn in the CTA's colour: $n", n > 100)
    }
}
