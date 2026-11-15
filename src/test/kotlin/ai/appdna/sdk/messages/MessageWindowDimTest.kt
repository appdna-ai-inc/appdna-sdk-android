package ai.appdna.sdk.messages

import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The window an in-app message is shown in does not dim the app behind it.
 *
 * A `Dialog` dims everything behind it by default (`FLAG_DIM_BEHIND` at the theme's
 * `backgroundDimAmount`). The message dialog never cleared it, so a banner and a tooltip drew over a
 * darkened app, and a modal was darkened twice: by the window and by its own 0.4 scrim. iOS presents the
 * message over the app with a clear background, so the only backdrop is the one the view draws.
 *
 * Read off the REAL dialog both hosts build ([InAppMessageDialog.build]), shown on a real activity, for
 * every message type. THE CONTROL, in the same test: a plain dialog built on the same activity IS dimmed
 * as this harness reads it, so a pass cannot come from a harness that cannot see a window dim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MessageWindowDimTest {

    private fun message(type: String): MessageConfig =
        MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to "synthetic",
                "message_type" to type,
                "content" to mapOf("title" to "A synthetic title", "body" to "Body copy", "cta_text" to "Go"),
                "trigger_rules" to mapOf("event" to "e"),
            ),
        ) ?: error("message did not parse")

    private data class Dim(val flag: Boolean, val amount: Float)

    private fun dimOf(dialog: ComponentDialog): Dim {
        val attrs = dialog.window!!.attributes
        return Dim(flag = attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0, amount = attrs.dimAmount)
    }

    @Test
    fun noMessageTypeDimsTheAppBehindIt() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        // Control: an ordinary dialog on this activity dims what is behind it, and this harness sees it.
        val plain = ComponentDialog(activity)
        plain.show()
        shadowOf(Looper.getMainLooper()).idle()
        val plainDim = dimOf(plain)
        assertTrue("control: a plain dialog is dimmed as this harness reads it: $plainDim", plainDim.flag && plainDim.amount > 0f)
        plain.dismiss()

        val seen = linkedMapOf<String, Dim>()
        for (type in listOf("banner", "tooltip", "modal", "fullscreen")) {
            val dialog = InAppMessageDialog.build(activity, message(type), onCTATap = {}, onDismiss = {})
            dialog.show()
            shadowOf(Looper.getMainLooper()).idle()
            seen[type] = dimOf(dialog)
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(
            "no message window dims the app; each view draws its own backdrop: $seen",
            listOf("banner", "tooltip", "modal", "fullscreen").associateWith { Dim(flag = false, amount = 0f) },
            seen,
        )
    }
}
