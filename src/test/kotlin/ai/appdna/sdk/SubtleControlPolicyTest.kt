package ai.appdna.sdk

import ai.appdna.sdk.paywalls.PaywallTextDefaults
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * The POLICY behind the three defaults that were hard-coded white.
 *
 * `SubtleControlLightPageSnapshotTest` is the other half: it renders the three sections on a white
 * page so the pixels can be looked at and counted. This one asserts the rule those pixels follow,
 * and in particular the promise that makes the change safe to ship.
 *
 * 🔴 ON A DARK PAGE THE RESOLVED COLOUR IS THE CONSTANT IT REPLACED. `readable` returns `#FFFFFF`
 * for any page dark enough for white to pass WCAG AA, so `subtleOnPage` is `Color.White` at the
 * same 30% alpha and a live dark paywall draws exactly the pixels it drew before. The rule here is
 * that "a guess that moves pixels on live paywalls is worse than a known inconsistency"; this is
 * how the change avoids being one. An image cannot assert "identical to what this used to be" — a
 * value comparison can.
 */
class SubtleControlPolicyTest {

    private val whitePage = "#FFFFFF"
    private val darkPage = "#0B1220"

    /**
     * 30% as Compose stores it. `Color.copy(alpha = 0.3f)` quantises to 8 bits — 77/255 =
     * 0.3019608 — so comparing the result against the literal `0.3f` fails by 0.002. Comparing
     * against the same round trip is the honest form, and it still catches an alpha that is a
     * different VALUE rather than a different rounding.
     */
    private val quantised30 = Color.White.copy(alpha = 0.3f).alpha

    @Test
    fun aDarkPageResolvesToExactlyTheConstantThisReplaced() {
        assertEquals(
            "a dark page must still resolve to white at 30%",
            Color.White.copy(alpha = 0.3f),
            PaywallTextDefaults.subtleOnPage(darkPage),
        )
        assertEquals(
            "and an opaque element to plain white",
            Color.White,
            PaywallTextDefaults.readable(darkPage),
        )
    }

    @Test
    fun aWhitePageResolvesToSomethingElseOrTheFixIsANoOp() {
        val subtle = PaywallTextDefaults.subtleOnPage(whitePage)
        assertNotEquals("a white page must NOT resolve to white", Color.White.copy(alpha = 0.3f), subtle)
        assertTrue("and what it resolves to must be dark", subtle.red < 0.5f && subtle.green < 0.5f)
        assertEquals("with the authored alpha kept", quantised30, subtle.alpha, 0.0001f)
    }

    /** Every page the console can set, not only the two extremes. */
    @Test
    fun everyPageResolvesToSomethingThatContrastsWithIt() {
        for (page in listOf("#FFFFFF", "#F3F4F6", "#0B1220", "#111827", "#6366F1", "#808080")) {
            val subtle = PaywallTextDefaults.subtleOnPage(page)
            val expected = PaywallTextDefaults.readable(page)
            assertEquals("$page must take the readable hue", expected.red, subtle.red, 0.001f)
            assertEquals("$page must keep the subtle alpha", quantised30, subtle.alpha, 0.0001f)
        }
    }
}
