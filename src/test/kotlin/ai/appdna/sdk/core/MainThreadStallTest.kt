package ai.appdna.sdk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The threshold, and the reason it is where it is.
 *
 * The watchdog itself needs a `Choreographer` and a foreground activity, so what is asserted here
 * is the decision it makes: which gaps count. That is the part a future change would get wrong —
 * a threshold lowered to "janky" turns the log into noise on an emulator drawing a map with
 * software GL, and one raised past an ANR catches nothing before the system kills the app.
 */
class MainThreadStallTest {

    @Test
    fun `a frame-sized gap is not a stall`() {
        assertFalse("16ms is one frame at 60Hz — the normal case", MainThreadStall.isStall(16))
        assertFalse("and 250ms is jank, not a missed lifecycle deadline", MainThreadStall.isStall(250))
    }

    @Test
    fun `a gap in the lifecycle-deadline band is`() {
        assertTrue(MainThreadStall.isStall(MainThreadStall.STALL_MS))
        assertTrue("the customer's sighting was a pause that never returned", MainThreadStall.isStall(3_000))
    }

    @Test
    fun `the threshold sits between jank and an ANR`() {
        // 🔴 The band is the whole point. Below it the system is unhappy but copes; above ~5s it
        // has already shown an ANR dialog, by which time the log is about the symptom again.
        assertTrue("must be well past a dropped frame", MainThreadStall.STALL_MS > 300)
        assertTrue("and well short of an ANR", MainThreadStall.STALL_MS < 5_000)
    }
}
