package ai.appdna.sdk.core

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A presentation queued behind another runs when the first is dismissed.
 *
 * `onDismissed` took the next entry with `presentationQueue.removeFirst()`. Compiled against API 35 that
 * binds to `java.util.List.removeFirst` (API 35 / JDK 21), so on every device below Android 15 — and
 * on this JDK 17 test JVM — dismissing a presentation with one queued behind it threw
 * `NoSuchMethodError`. Lint `NewApi` (the sdk-ci.yml gate) reports it. NEGATIVE CONTROL: with
 * `removeFirst()` back, this test fails with `NoSuchMethodError`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PresentationCoordinatorQueueTest {

    @Test
    fun `a queued presentation runs when the current one is dismissed`() {
        val c = PresentationCoordinator.shared
        val ran = mutableListOf<String>()
        assertTrue(c.requestPresentation(PresentationCoordinator.PresentationType.SCREEN) { ran += "first" })
        assertFalse("queued behind the first",
            c.requestPresentation(PresentationCoordinator.PresentationType.MESSAGE) { ran += "second" })

        c.onDismissed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("first", "second"), ran)
        c.onDismissed() // the second is dismissed; the coordinator is idle again
    }
}
