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

    private fun paywall(
        cta: Map<String, Any> = mapOf("text" to "Continue", "bg_color" to "#FF00FF"),
        badgeData: Map<String, Any> = emptyMap(),
    ): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_accent",
        mapOf(
            "id" to "pw_accent", "name" to "Accent",
            "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            "sections" to listOf(mapOf(
                "id" to "sec_badge", "type" to "social_proof",
                "data" to mapOf("sub_type" to "trial_badge", "text" to "WWWWWWWW WWWW") + badgeData,
            )),
            "cta" to cta,
        ),
    ) ?: error("paywall did not parse")

    /** Pixels in the upper 55% of the frame (above the CTA) matching each of [matches]. One render per test. */
    private fun countUpper(config: PaywallConfig, name: String, vararg matches: (Int, Int, Int) -> Boolean): List<Int> {
        compose.setContent { PaywallScreen(config = config, onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {}) }
        compose.waitForIdle()
        val path = "build/tmp/paywall-accent/$name.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        val n = IntArray(matches.size)
        for (y in 0 until bmp.height * 55 / 100) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            matches.forEachIndexed { i, m -> if (m(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))) n[i]++ }
        }
        return n.toList()
    }

    private val magenta = { r: Int, g: Int, b: Int -> r > 180 && g < 110 && b > 180 }
    private val green = { r: Int, g: Int, b: Int -> r < 90 && g > 150 && b < 90 }

    /** A pin, not a fix: the parser already folds `cta.style.bg_color` (what the generator writes) into the accent. */
    @Test
    fun `the generated shape - cta style bg_color - is the paywall's accent too`() {
        val n = countUpper(paywall(cta = mapOf("text" to "Continue", "style" to mapOf("bg_color" to "#FF00FF", "text_color" to "#FFFFFF"))), "badge-style", magenta).single()
        println("[paywall-accent] style magenta=$n")
        assertTrue("the badge text is drawn in cta.style.bg_color: $n", n > 100)
    }

    @Test
    fun `an authored badge colour wins over the paywall's accent`() {
        val (g, m) = countUpper(paywall(badgeData = mapOf("badge_color" to "#00C000")), "badge-color", green, magenta)
        println("[paywall-accent] badge_color green=$g magenta=$m")
        assertTrue("the badge is drawn in its authored colour: $g", g > 100)
        assertTrue("and not in the CTA's: $m", m < 20)
    }

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
