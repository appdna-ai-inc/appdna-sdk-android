package ai.appdna.sdk.deeplinks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * THE FIRST-LAUNCH CHECK READS THE CLIPBOARD ONLY WHEN IT HOLDS A LINK.
 *
 * Reading clip CONTENT shows Android 12+'s "pasted from your clipboard" toast, so the check asks the
 * description-only questions first and reads only when the system classified the text as a URL.
 * These drive the REAL manager with a recording clipboard, so "was the content read?" is observed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeferredLinkClipboardTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        ctx.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class Recording(val text: Boolean, val url: Boolean, val content: String?) : ClipboardReading {
        @Volatile var reads = 0
        @Volatile var clears = 0
        @Volatile var readOnMain: Boolean? = null
        override fun hasText() = text
        override fun isProbableWebUrl() = url
        override fun readText(): String? {
            reads++
            readOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            return content
        }
        override fun clear() { clears++ }
    }

    private fun check(cb: ClipboardReading) {
        val latch = java.util.concurrent.CountDownLatch(1)
        DeferredDeepLinkManager(ctx, "org", "app", null, cb).checkDeferredDeepLink { latch.countDown() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (latch.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) return
        }
        throw AssertionError("checkDeferredDeepLink never called back")
    }

    @Test fun `text that is not a link is never read`() {
        val cb = Recording(text = true, url = false, content = "a shopping list someone copied")
        check(cb)
        assertEquals("the read is what toasts; it must not happen", 0, cb.reads)
        assertEquals(0, cb.clears)
    }

    /** 🔴 RED CONTROL for the test above: the same harness DOES see a read when the gate opens. */
    @Test fun `our link is read off the main thread and cleared`() {
        val cb = Recording(text = true, url = true, content = "https://links.example.com/?appdna_visitor=v_123")
        check(cb)
        assertEquals(1, cb.reads)
        assertEquals(false, cb.readOnMain)
        assertEquals("our marker is one-time and must be cleared", 1, cb.clears)
    }

    @Test fun `someone else's link is read but left where they put it`() {
        val cb = Recording(text = true, url = true, content = "https://news.example.org/article/42")
        check(cb)
        assertEquals(1, cb.reads)
        assertEquals(0, cb.clears)
    }

    /**
     * The REAL system clipboard on API 34: plain text the system never classified must not count as
     * a link, so `SystemClipboard` refuses the read. And with nothing copied it reports no text.
     */
    @Test fun `the system clipboard refuses unclassified text on Android 12+`() {
        val sys = SystemClipboard(ctx)
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.clearPrimaryClip()
        assertFalse(sys.hasText())
        manager.setPrimaryClip(ClipData.newPlainText("", "https://links.example.com/?appdna_visitor=v_123"))
        assertTrue(sys.hasText())
        assertFalse(
            "a clip whose classification is not COMPLETE must not be treated as a link",
            sys.isProbableWebUrl(),
        )
    }

    /**
     * Android 11 and below: no "pasted" toast exists and the system classifies nothing, so the gate lets the read
     * happen, as it always did, and only our own marker is used. 🔴 The test above, on 34, is this test's control:
     * the same unclassified text is refused there.
     */
    @Test @Config(manifest = Config.NONE, sdk = [30])
    fun `before Android 12 the system clipboard reads as before`() {
        val sys = SystemClipboard(ctx)
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("", "appdna:visitor:v_legacy"))
        assertTrue(sys.hasText())
        assertTrue("no classification before 12: the read is allowed", sys.isProbableWebUrl())
        assertEquals("v_legacy", DeferredLinkPasteboard.visitorId(sys.readText()))
        sys.clear()
        assertFalse("our marker is cleared", sys.hasText())
    }
}
