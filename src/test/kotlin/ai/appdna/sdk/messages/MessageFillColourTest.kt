package ai.appdna.sdk.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message's card is filled with exactly its authored `background_color`.
 *
 * Material 3 tints a card whose colour equals `colorScheme.surface` by its tonal elevation, and the message renderer
 * seeds `surface` from `background_color`, so an authored white banner and tooltip drew grey-purple — iOS and the
 * console preview draw the authored colour. Rendered through the REAL [InAppMessageView] on a black backdrop and read
 * off the pixels just inside the card, beside the title. THE CONTROL, in every case: the sampled pixel is not the
 * black backdrop, so a pass cannot come from a sample taken outside the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MessageFillColourTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(type: String, bg: String): MessageConfig =
        MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to "synthetic",
                "message_type" to type,
                "content" to mapOf(
                    "title" to "A synthetic title",
                    "body" to "Body copy",
                    "cta_text" to "Go",
                    "background_color" to bg,
                    "text_color" to "#111827",
                    "button_color" to "#1D4ED8",
                ),
                "trigger_rules" to mapOf("event" to "e"),
            ),
        ) ?: error("message did not parse")

    @Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private var shown by mutableStateOf<MessageConfig?>(null)
    private var composed = false

    /** The colour just inside the card, 8dp before the title's leading edge, as 0xRRGGBB. */
    private fun fillOf(config: MessageConfig): Int {
        if (!composed) {
            composed = true
            compose.setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    shown?.let { cfg -> key(cfg) { InAppMessageView(config = cfg, onCTATap = {}, onDismiss = {}, isDark = false) } }
                }
            }
        }
        shown = config
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val title = compose.onNodeWithText("A synthetic title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inset = with(compose.density) { 8.dp.toPx() }
        // Roborazzi, not `captureToImage()`: the latter waits on a redraw this tree never reports.
        val path = "build/tmp/message-fill/${config.message_type}-${config.content.background_color}.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        return bmp.getPixel((title.left - inset).toInt(), title.center.y.toInt()) and 0xFFFFFF
    }

    @Test
    fun aBannerTooltipAndModalAreFilledWithTheirAuthoredColour() {
        val seen = linkedMapOf<String, String>()
        for (type in listOf("banner", "tooltip", "modal")) {
            for (bg in listOf("#FFFFFF", "#FDE68A")) {
                val rgb = fillOf(message(type, bg))
                assertTrue("control: $type $bg sampled the black backdrop, not the card", rgb != 0x000000)
                seen["$type $bg"] = String.format("#%06X", rgb)
            }
        }
        assertEquals(
            "each card is its authored background_color: $seen",
            listOf("banner", "tooltip", "modal").flatMap { t -> listOf("$t #FFFFFF" to "#FFFFFF", "$t #FDE68A" to "#FDE68A") }.toMap(),
            seen,
        )
    }
}
