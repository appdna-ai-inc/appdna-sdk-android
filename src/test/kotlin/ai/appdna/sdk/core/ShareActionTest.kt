package ai.appdna.sdk.core

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one share rule every surface calls: what counts as something to share, and the exact
 * intent the share sheet is started with. The surface tests (onboarding button, Multi-buttons child,
 * paywall extra button, message CTA) prove each surface reaches THIS; this proves what it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareActionTest {

    // ── payload: trimming and the "nothing to share" rule ─────────────────────────────────────

    @Test
    fun `both halves are trimmed`() {
        assertEquals(
            ShareAction.SharePayload(text = "I finished my first week!", url = "https://example.com/invite"),
            ShareAction.payload("  I finished my first week!  ", " https://example.com/invite "),
        )
    }

    @Test
    fun `a blank half is absent, not empty`() {
        assertEquals(ShareAction.SharePayload(text = null, url = "https://example.com/x"), ShareAction.payload("   ", "https://example.com/x"))
        assertEquals(ShareAction.SharePayload(text = "Hello", url = null), ShareAction.payload("Hello", " \n "))
        assertEquals(ShareAction.SharePayload(text = "Hello", url = null), ShareAction.payload("Hello", null))
    }

    @Test
    fun `nothing to share is null`() {
        assertNull(ShareAction.payload(null, null))
        assertNull(ShareAction.payload("", ""))
        assertNull(ShareAction.payload("  \t ", null))
        assertNull(ShareAction.payload(null, "   "))
    }

    // ── the chooser intent ───────────────────────────────────────────────────────────────────

    private fun inner(chooser: Intent): Intent {
        assertEquals("the sheet is a chooser", Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: error("chooser carries no inner intent")
    }

    @Test
    fun `text and link share as one text, the link on its own line`() {
        val send = inner(ShareAction.buildChooserIntent(ShareAction.SharePayload("Join me", "https://example.com/invite")))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Join me\nhttps://example.com/invite", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `text only shares the text alone`() {
        val send = inner(ShareAction.buildChooserIntent(ShareAction.SharePayload("Join me", null)))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Join me", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `link only shares the link alone, with no leading newline`() {
        val send = inner(ShareAction.buildChooserIntent(ShareAction.SharePayload(null, "https://example.com/invite")))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("https://example.com/invite", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    // ── open: starts the sheet, from an Activity or not ──────────────────────────────────────

    @Test
    fun `open from an activity starts the chooser without a new task`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertTrue(ShareAction.open(activity, "  Hi ", null, surface = "test"))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("a sheet was started", started)
        assertEquals("Hi", inner(started).getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `open from the application adds a new task`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ShareAction.open(app, null, "https://example.com/x", surface = "test"))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `nothing to share opens nothing`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertFalse(ShareAction.open(activity, "   ", null, surface = "test"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
