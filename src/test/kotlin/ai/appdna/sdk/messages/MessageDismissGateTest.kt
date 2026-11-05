package ai.appdna.sdk.messages

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * the console's "Allow Dismiss" switch and "Dismiss Delay" OPERATED on the real renderer.
 *
 * The shared fixture `message_dismiss_policy` pins the RULE. This drives the views that are meant to
 * obey it: the ✕ found by its accessibility label (what TalkBack and a tap reach), the modal backdrop
 * tapped, the delay waited out on the compose clock, and the system back button pressed on the REAL
 * dialog both hosts build ([InAppMessageDialog]).
 *
 * Every "it is blocked" assertion has its control in the same test: the same message with dismissal
 * allowed must show the ✕ / honour the tap / close on back, so a pass cannot come from a harness that
 * cannot see a ✕, cannot tap, or cannot press back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MessageDismissGateTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(type: String, extra: Map<String, Any>): MessageConfig =
        MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to "synthetic",
                "message_type" to type,
                "content" to mapOf(
                    "title" to "A synthetic title",
                    "body" to "Body copy",
                    "cta_text" to "Primary action",
                    "secondary_cta_text" to "Secondary action",
                ) + extra,
                "trigger_rules" to mapOf("event" to "e"),
            ),
        ) ?: error("message did not parse")

    /** The ✕'s accessibility label — `R.string.appdna_a11y_message_close`, or the tooltip's own. */
    private fun closeLabel(type: String) = if (type == "tooltip") "Close" else "Close message"

    private class Calls { var cta = 0; var dismiss = 0 }

    // `setContent` may be called once per test, so one composition swaps the message; `key` gives each
    // message a fresh state (its own delay clock), as a newly presented dialog has.
    private var shown by mutableStateOf<Pair<MessageConfig, Calls>?>(null)
    private var composed = false

    private fun show(config: MessageConfig): Calls {
        val calls = Calls()
        if (!composed) {
            composed = true
            compose.setContent {
                shown?.let { (cfg, c) ->
                    key(cfg, c) {
                        InAppMessageView(config = cfg, onCTATap = { c.cta++ }, onDismiss = { c.dismiss++ }, isDark = false)
                    }
                }
            }
        }
        shown = config to calls
        compose.mainClock.advanceTimeByFrame()
        return calls
    }

    @Test
    fun `not dismissable hides the close control on every message type, and allowed draws it`() {
        for (type in listOf("modal", "banner", "fullscreen", "tooltip")) {
            // CONTROL first: allowed → the ✕ is there and closes the message.
            val allowed = show(message(type, mapOf("dismiss_allowed" to true)))
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription(closeLabel(type)).assertExists("[$type] control: the ✕ of a dismissable message")
            compose.onNodeWithContentDescription(closeLabel(type)).performClick()
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals("[$type] control: tapping the ✕ dismisses", 1, allowed.dismiss)

            val blocked = show(message(type, mapOf("dismiss_allowed" to false)))
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithContentDescription(closeLabel(type)).assertDoesNotExist()
            assertEquals("[$type] nothing dismissed it", 0, blocked.dismiss)
            // The authored buttons stay — as the console preview shows them.
            compose.onNodeWithText("Primary action").assertExists("[$type] the CTA stays")
            compose.onNodeWithText("Primary action").performClick()
            assertEquals("[$type] the CTA still works", 1, blocked.cta)
        }
    }

    @Test
    fun `a dismiss delay shows the close control only after N seconds`() {
        compose.mainClock.autoAdvance = false
        show(message("modal", mapOf("dismiss_allowed" to true, "dismiss_delay" to 2)))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Close message").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_800) // 1.9 s
        compose.onNodeWithContentDescription("Close message").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(300) // 2.2 s
        compose.onNodeWithContentDescription("Close message").assertExists("the ✕ appears once the delay has passed")
    }

    @Test
    fun `a backdrop tap dismisses a modal only while dismissal is allowed`() {
        // CONTROL: allowed → the backdrop (top-left corner, outside the card) dismisses.
        val allowed = show(message("modal", mapOf("dismiss_allowed" to true)))
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("control: the backdrop tap reaches onDismiss", 1, allowed.dismiss)

        val blocked = show(message("modal", mapOf("dismiss_allowed" to false)))
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("a non-dismissable modal ignores the backdrop", 0, blocked.dismiss)

        val delayed = show(message("modal", mapOf("dismiss_delay" to 3)))
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(100)
        assertEquals("inside its delay the backdrop is ignored", 0, delayed.dismiss)
        compose.mainClock.advanceTimeBy(3_000)
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("after its delay the backdrop dismisses", 1, delayed.dismiss)
    }
}

/**
 * The system back button, on the REAL dialog both hosts present a message in. Separate class: it needs
 * an Activity of its own rather than the compose rule's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MessageDialogBackPressTest {

    private fun message(extra: Map<String, Any>): MessageConfig =
        MessageConfigParser.parseSingleMessage(
            mapOf(
                "name" to "synthetic",
                "message_type" to "modal",
                "content" to mapOf("title" to "A synthetic title", "cta_text" to "Primary action") + extra,
                "trigger_rules" to mapOf("event" to "e"),
            ),
        ) ?: error("message did not parse")

    private class Result(val dismissed: Int, val showing: Boolean)

    private fun pressBack(extra: Map<String, Any>, waitMs: Long = 300): Result {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var dismissed = 0
        val dialog = InAppMessageDialog.build(
            activity,
            message(extra),
            onCTATap = { it.dismiss() },
            onDismiss = { dismissed++; it.dismiss() },
        )
        dialog.show()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(waitMs))
        // The message's delay is a coroutine `delay` on the UI dispatcher, which has no Delay of its own:
        // kotlinx times it on its real-time executor, then posts the resume to the main looper. So the
        // wait is REAL time as well as looper time, as it is on a device.
        Thread.sleep(waitMs)
        shadowOf(Looper.getMainLooper()).idle()
        dialog.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        val result = Result(dismissed, dialog.isShowing)
        // Tear the window down so the message's composition is disposed and its delay coroutine is
        // CANCELLED. Left running, that real-time delay resumes during a LATER test and leaves a Compose
        // dispatch its looper can never run — RobolectricTestApplication's guard then fails that test.
        dialog.dismiss()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return result
    }

    @Test
    fun `back closes a dismissable message through onDismiss`() {
        val r = pressBack(mapOf("dismiss_allowed" to true))
        assertEquals("control: back reaches the tracked onDismiss", 1, r.dismissed)
        assertFalse("control: the dialog closed", r.showing)
    }

    @Test
    fun `back does nothing to a message that may not be dismissed`() {
        val r = pressBack(mapOf("dismiss_allowed" to false))
        assertEquals(0, r.dismissed)
        assertTrue("the dialog is still showing", r.showing)
    }

    @Test
    fun `back does nothing inside the dismiss delay, and closes after it`() {
        val early = pressBack(mapOf("dismiss_delay" to 5), waitMs = 300)
        assertEquals(0, early.dismissed)
        assertTrue("inside the delay the dialog stays", early.showing)

        val late = pressBack(mapOf("dismiss_delay" to 1), waitMs = 1_500)
        assertEquals("after the delay back dismisses", 1, late.dismissed)
        assertFalse(late.showing)
    }
}
