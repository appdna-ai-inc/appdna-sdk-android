package ai.appdna.sdk.messages

import ai.appdna.sdk.AppDNAInAppMessageDelegate
import ai.appdna.sdk.core.ShareAction
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * An in-app message whose CTA is `{type: "share", text, url}` (#1797).
 *
 * Parsing is asserted on the REAL `MessageConfigParser`; the tap is driven through the REAL
 * `MessageManager` — an event presents the message on the foreground activity, the CTA is clicked
 * in the dialog, and the message is checked to have been reported as clicked (the delegate's
 * `onMessageAction`, which sits beside `in_app_message_clicked` in the same callback), to have
 * closed, and to have started the share sheet with the authored text and link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MessageShareCtaTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun message(cta: Any?): MessageConfig = MessageConfigParser.parseSingleMessage(
        mapOf(
            "name" to "synthetic",
            "message_type" to "modal",
            "content" to buildMap<String, Any> {
                put("title", "A synthetic title")
                put("body", "Body copy")
                put("cta_text", "Share it")
                if (cta != null) put("cta_action", cta)
            },
            "trigger_rules" to mapOf("event" to "share_moment", "frequency" to "every_time"),
        ),
    ) ?: error("message did not parse")

    // ── parsing ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a share cta parses its text and link, and the payload is trimmed`() {
        val action = message(mapOf("type" to "share", "text" to "  Come along  ", "url" to " https://example.com/m ")).content.cta_action
        assertEquals("share", action?.type)
        assertEquals(ShareAction.SharePayload("Come along", "https://example.com/m"), action?.sharePayload)
        assertNull("a share is not a link to open", action?.linkToOpen)
    }

    @Test
    fun `a share cta with only text, or only a link`() {
        assertEquals(
            ShareAction.SharePayload("Come along", null),
            message(mapOf("type" to "share", "text" to "Come along")).content.cta_action?.sharePayload,
        )
        assertEquals(
            ShareAction.SharePayload(null, "https://example.com/m"),
            message(mapOf("type" to "share", "url" to "https://example.com/m")).content.cta_action?.sharePayload,
        )
    }

    @Test
    fun `a blank share cta has no payload, and other types never do`() {
        assertNull(message(mapOf("type" to "share", "text" to "   ")).content.cta_action?.sharePayload)
        assertNull(message("share").content.cta_action?.sharePayload)
        assertEquals("share", message("share").content.cta_action?.type)
        assertNull(message(mapOf("type" to "open_url", "url" to "https://example.com", "text" to "x")).content.cta_action?.sharePayload)
    }

    // ── the tap, through the real manager ───────────────────────────────────────────────────

    private class Recorder : AppDNAInAppMessageDelegate {
        val actions = mutableListOf<Pair<String, String>>()
        val dismissed = mutableListOf<String>()
        override fun onMessageAction(messageId: String, action: String, data: Map<String, Any>?) {
            actions += messageId to action
        }
        override fun onMessageDismissed(messageId: String) { dismissed += messageId }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Presents [cta]'s message through a real [MessageManager], clicks its CTA, and returns the recorder. */
    private fun presentAndTap(cta: Any): Recorder {
        compose.waitForIdle() // the host activity is resumed
        val mm = MessageManager(compose.activity.applicationContext, configProvider = { mapOf("m_share" to message(cta)) })
        val host = Recorder()
        mm.delegate = host
        mm.onEvent("share_moment", emptyMap())
        idle()
        compose.waitForIdle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the message was presented", dialog)
        assertTrue(dialog.isShowing)
        compose.onNodeWithText("Share it").performClick()
        compose.waitForIdle()
        idle()
        assertFalse("the message closed on the CTA", dialog.isShowing)
        assertEquals("the CTA tap was reported, as for any CTA", listOf("m_share" to (message(cta).content.cta_action?.type ?: "dismiss")), host.actions)
        assertEquals(listOf("m_share"), host.dismissed)
        return host
    }

    @Test
    fun `tapping a share cta dismisses the message and opens the share sheet`() {
        presentAndTap(mapOf("type" to "share", "text" to "  Come along  ", "url" to " https://example.com/m "))
        val started = shadowOf(compose.activity).nextStartedActivity ?: error("no share sheet was started")
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Come along\nhttps://example.com/m", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a blank share cta only dismisses`() {
        presentAndTap(mapOf("type" to "share", "text" to "  "))
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
