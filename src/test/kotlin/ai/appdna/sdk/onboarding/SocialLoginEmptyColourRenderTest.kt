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
 * RENDERED, through the real `ContentBlockRendererView`: a `social_login` Google provider whose `bg_color` and
 * `text_color` are `""` (a cleared picker) draws the brand default — the #4285F4 filled button — as iOS does.
 * `parseColor("")` is Transparent, so the button drew nothing. THE CONTROL: the same provider with no colour keys.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SocialLoginEmptyColourRenderTest {
    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private fun block(map: Map<String, Any>): ContentBlock =
        OnboardingConfigParser.parseStepForTest(mapOf("id" to "s", "type" to "custom", "content_blocks" to listOf(map)))
            ?.config?.content_blocks?.firstOrNull() ?: error("block did not parse")

    /** Pixels matching [match] in one block rendered on a [bgHex] step. */
    private fun count(b: ContentBlock, bgHex: String, tag: String, match: (Int, Int, Int) -> Boolean): Int {
        val path = "build/tmp/social-empty/$tag.png"
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

    private val googleBlue = { r: Int, g: Int, b: Int -> kotlin.math.abs(r - 0x42) <= 6 && kotlin.math.abs(g - 0x85) <= 6 && kotlin.math.abs(b - 0xF4) <= 6 }

    private fun login(empty: Boolean) = block(mapOf(
        "id" to "sl", "type" to "social_login",
        "providers" to listOf(buildMap<String, Any> {
            put("type", "google"); put("enabled", true); put("label", "Continue with Google")
            if (empty) { put("bg_color", ""); put("text_color", "") }
        }),
    ))

    @Test
    fun `an empty provider colour is unset and draws the brand default`() {
        val unset = count(login(false), "#FFFFFF", "unset") { r, g, b -> googleBlue(r, g, b) }
        val empty = count(login(true), "#FFFFFF", "empty") { r, g, b -> googleBlue(r, g, b) }
        println("[social-empty] unset=$unset empty=$empty")
        assertTrue("control: the brand default draws ($unset)", unset > 2000)
        assertTrue("an empty colour drew no brand fill ($empty vs $unset)", empty > unset / 2)
    }

    /** An email provider with the block's accent_color "" falls through to the block's bg_color, as on iOS. */
    @Test
    fun `an empty block accent colour falls through to the block bg colour`() {
        val green = { r: Int, g: Int, b: Int -> r < 20 && kotlin.math.abs(g - 0xA0) <= 6 && b < 20 }
        fun email(accent: String?) = block(buildMap<String, Any> {
            put("id", "sl"); put("type", "social_login"); put("bg_color", "#00A000")
            if (accent != null) put("accent_color", accent)
            put("providers", listOf(mapOf("type" to "email", "enabled" to true, "label" to "Continue with email")))
        })
        val unset = count(email(null), "#FFFFFF", "email-unset") { r, g, b -> green(r, g, b) }
        val empty = count(email(""), "#FFFFFF", "email-empty") { r, g, b -> green(r, g, b) }
        println("[social-empty accent] unset=$unset empty=$empty")
        assertTrue("control: the block bg colour draws ($unset)", unset > 2000)
        assertTrue("an empty accent_color drew no fill ($empty vs $unset)", empty > unset / 2)
    }
}
