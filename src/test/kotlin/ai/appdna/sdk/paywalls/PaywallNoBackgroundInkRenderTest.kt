package ai.appdna.sdk.paywalls

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
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
 * RENDERED, through the real `PaywallScreen`: a header whose paywall authored no background is drawn
 * in the ink of the page behind it. In a Light theme the page is light, and the title was white on it. THE
 * CONTROLS: the same paywall in a Dark theme draws light ink, and an authored dark page keeps its light ink.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallNoBackgroundInkRenderTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun paywall(background: Map<String, Any>?): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_ink",
        buildMap {
            put("id", "pw_ink"); put("name", "Ink")
            background?.let { put("background", it) }
            put("sections", listOf(mapOf(
                "id" to "sec_header", "type" to "header",
                "data" to mapOf("title" to "WWWWWWWWWWW"),
            )))
        },
    ) ?: error("paywall did not parse")

    private data class Shot(val pw: PaywallConfig, val dark: Boolean)
    private val state = mutableStateOf<Shot?>(null)

    private fun count(background: Map<String, Any>?, darkTheme: Boolean, match: (Double) -> Boolean): Int {
        val shot = Shot(paywall(background), darkTheme)
        if (state.value == null) {
            compose.mainClock.autoAdvance = false
            state.value = shot
            compose.setContent {
                val s = state.value ?: return@setContent
                MaterialTheme(colorScheme = if (s.dark) darkColorScheme() else lightColorScheme()) {
                    androidx.compose.runtime.key(s) {
                        PaywallScreen(config = s.pw, onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
                    }
                }
            }
        } else {
            state.value = shot
        }
        compose.mainClock.advanceTimeBy(1_000)
        val path = "build/tmp/paywall-no-background/${background?.size ?: "none"}-$darkTheme.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
        var n = 0
        // The header's band: below the system bars and above the first section after it.
        for (y in 0 until bmp.height / 3) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            val lum = (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) +
                0.0722 * android.graphics.Color.blue(c)) / 255
            if (match(lum)) n++
        }
        return n
    }

    private val darkInk = { lum: Double -> lum < 0.45 }
    private val lightInk = { lum: Double -> lum > 0.75 }

    @Test
    fun `a paywall with no background reads its header on a light theme`() {
        val n = count(null, darkTheme = false, darkInk)
        println("[no-bg light] dark-ink=$n")
        assertTrue("the header is the page's colour ($n dark-ink pixels)", n > 800)
    }

    @Test
    fun `a paywall with no background reads its header on a dark theme`() {
        val n = count(null, darkTheme = true, lightInk)
        println("[no-bg dark] light-ink=$n")
        assertTrue("the header is the page's colour ($n light-ink pixels)", n > 800)
    }

    @Test
    fun `a colour background with no value draws dark ink, the page it paints being white`() {
        val n = count(mapOf("type" to "color"), darkTheme = false, darkInk)
        println("[color-no-value] dark-ink=$n")
        assertTrue("the header is the page's colour ($n dark-ink pixels)", n > 800)
    }

    @Test
    fun `an authored dark page keeps its light ink`() {
        val n = count(mapOf("type" to "color", "value" to "#111827"), darkTheme = false, lightInk)
        println("[authored-dark] light-ink=$n")
        assertTrue("an authored dark page lost its ink ($n)", n > 800)
    }
}
