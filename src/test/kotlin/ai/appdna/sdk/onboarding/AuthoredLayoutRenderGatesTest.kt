package ai.appdna.sdk.onboarding

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render rules behind a batch of authored-layout bugs on Android.
 *
 * Each of these bugs shipped because the rule lived inside a Composable, where the only way to check
 * it was to look at a screen. They are pure functions now precisely so a regression fails here
 * instead of in a tester's screenshot.
 */
class AuthoredLayoutRenderGatesTest {

    // ── the stray "Skip" on every Android screen ──────────────────────────────────────

    @Test
    fun `skip affordance needs BOTH the flag and a handler`() {
        // The regression: a change made the handler unconditional, and a render gated on the
        // handler alone started drawing "Skip" under the CTA of every step.
        assertFalse(
            "a handler without skip_enabled must NOT render Skip — this is #653",
            shouldRenderSkipAffordance(skipEnabled = null, hasSkipHandler = true),
        )
        assertFalse(shouldRenderSkipAffordance(skipEnabled = false, hasSkipHandler = true))
        assertTrue(shouldRenderSkipAffordance(skipEnabled = true, hasSkipHandler = true))
    }

    @Test
    fun `skip affordance never renders without a handler to invoke`() {
        assertFalse(shouldRenderSkipAffordance(skipEnabled = true, hasSkipHandler = false))
        assertFalse(shouldRenderSkipAffordance(skipEnabled = null, hasSkipHandler = false))
    }

    // ── horizontal_align ignored on a width-constrained block ───────────────────

    @Test
    fun `a percent-width block with an alignment needs the outer alignment box`() {
        // A 75%-wide button authored `center`, and one authored `right` — the reported shapes.
        assertTrue(needsOuterAlignmentBox(elementWidth = "75%", horizontalAlign = "center"))
        assertTrue(needsOuterAlignmentBox(elementWidth = "300px", horizontalAlign = "center"))
        assertTrue(needsOuterAlignmentBox(elementWidth = "50%", horizontalAlign = "right"))
    }

    @Test
    fun `an unconstrained width never needs it`() {
        // These already worked: the sizing box wraps a fillMaxWidth child, so the inner alignment
        // still has the full row. Wrapping them again would be a behaviour change, not a fix.
        assertFalse(needsOuterAlignmentBox(elementWidth = null, horizontalAlign = "center"))
        assertFalse(needsOuterAlignmentBox(elementWidth = "auto", horizontalAlign = "center"))
        assertFalse(needsOuterAlignmentBox(elementWidth = "fill", horizontalAlign = "center"))
    }

    @Test
    fun `no authored alignment means no wrapper, whatever the width`() {
        assertFalse(needsOuterAlignmentBox(elementWidth = "75%", horizontalAlign = null))
        assertFalse(needsOuterAlignmentBox(elementWidth = "300px", horizontalAlign = null))
    }

    // ── Multi-buttons row planning ─────────────────────────────────────────────────────

    @Test
    fun `three buttons at two per row is the two-then-one layout that was asked for`() {
        assertEquals(listOf(2, 1), multiButtonRowPlan(childCount = 3, perRow = 2))
    }

    @Test
    fun `full rows have no remainder row`() {
        assertEquals(listOf(2, 2), multiButtonRowPlan(childCount = 4, perRow = 2))
        assertEquals(listOf(3), multiButtonRowPlan(childCount = 3, perRow = 3))
        assertEquals(listOf(1, 1, 1), multiButtonRowPlan(childCount = 3, perRow = 1))
    }

    @Test
    fun `perRow is clamped to the one-to-three the element promises`() {
        // A 0 would divide by zero; anything above 3 is not a layout this element offers.
        assertEquals(listOf(1, 1), multiButtonRowPlan(childCount = 2, perRow = 0))
        assertEquals(listOf(3, 1), multiButtonRowPlan(childCount = 4, perRow = 9))
    }

    // ── a button could be SIZED or ALIGNED, never both ──────────────────

    /*
     * Three reports, one defect. The button hardcoded fillMaxWidth(), so `element_width: "auto"` —
     * the one value meaning "be as wide as your label" — was ignored. A "show more" button
     * authored `auto` + `center` rendered edge-to-edge.
     */

    @Test
    fun `auto is the only width that stops a button filling its row`() {
        assertFalse(buttonFillsWidth("auto"))
    }

    @Test
    fun `unset and fill still fill, so no published CTA changes`() {
        assertTrue(buttonFillsWidth(null))
        assertTrue(buttonFillsWidth("fill"))
        assertTrue(buttonFillsWidth("100%"))
    }

    @Test
    fun `a percentage still fills — the sizing box is what constrains it`() {
        // The button filling its already-constrained parent is what makes it exactly 75% wide.
        assertTrue(buttonFillsWidth("75%"))
        assertTrue(buttonFillsWidth("240px"))
    }

    // ── the per-child button border that nothing read ─────────────────────────────────

    /*
     * Reported after the element itself passed: "Border color doesn't apply — the border never
     * renders in that color, on device or in preview". It was true on ALL THREE surfaces. Every one
     * stroked a hardcoded 1.5dp ring in the BACKGROUND colour, and only for `variant: outline`, so a
     * filled button with border width 40 and a yellow border drew nothing whatsoever.
     */

    @Test
    fun `an authored width draws a ring on a filled button`() {
        // The exact case from the issue screenshot: a filled button at width 40 drew nothing.
        assertEquals(40.0, authoredButtonBorderWidth(authored = 40.0, variant = "primary"), 0.0)
        assertEquals(3.0, authoredButtonBorderWidth(authored = 3.0, variant = null), 0.0)
    }

    @Test
    fun `an unauthored button keeps exactly the border it had before`() {
        // Non-outline had no ring and must not grow one, or every published flow shifts.
        assertEquals(0.0, authoredButtonBorderWidth(authored = null, variant = "primary"), 0.0)
        assertEquals(0.0, authoredButtonBorderWidth(authored = null, variant = "text"), 0.0)
        // Outline rings itself at 1.5 — the long-standing default, kept.
        assertEquals(1.5, authoredButtonBorderWidth(authored = null, variant = "outline"), 0.0)
    }

    @Test
    fun `an authored zero removes the ring, including from an outline button`() {
        // "No border" has to be expressible, otherwise outline can never lose its ring.
        assertEquals(0.0, authoredButtonBorderWidth(authored = 0.0, variant = "outline"), 0.0)
        // A negative is nonsense rather than an inverted ring; clamp instead of passing it to Compose.
        assertEquals(0.0, authoredButtonBorderWidth(authored = -5.0, variant = "primary"), 0.0)
    }

    @Test
    fun `a blank colour counts as unset so the ring falls back to the button colour`() {
        // The console ColorPicker writes "" for transparent; stroking "nothing" would hide the edge.
        assertEquals(null, authoredButtonBorderColorHex(""))
        assertEquals(null, authoredButtonBorderColorHex("   "))
        assertEquals(null, authoredButtonBorderColorHex(null))
        assertEquals("#f9ff00", authoredButtonBorderColorHex("#f9ff00"))
    }

    @Test
    fun `a short last row reserves half the missing columns on each side`() {
        // The layout that was asked for: 3 buttons at 2 per row. The lone last button is ONE column
        // wide with half a column either side, so it sits under the two above it.
        assertEquals(0.5f, multiButtonFillerWeight(rowSize = 1, perRow = 2, stretchLastRow = false))
        assertEquals(1.0f, multiButtonFillerWeight(rowSize = 1, perRow = 3, stretchLastRow = false))
        assertEquals(0.5f, multiButtonFillerWeight(rowSize = 2, perRow = 3, stretchLastRow = false))
    }

    @Test
    fun `a full row reserves nothing`() {
        assertEquals(0f, multiButtonFillerWeight(rowSize = 2, perRow = 2, stretchLastRow = false))
        assertEquals(0f, multiButtonFillerWeight(rowSize = 3, perRow = 3, stretchLastRow = false))
    }

    @Test
    fun `stretch reserves nothing, which is what makes it stretch`() {
        // The regression this pins: with no filler the child keeps `weight(1f)` and fills the row,
        // so "center" and "stretch" rendered the SAME pixels. Two settings, one image.
        assertEquals(0f, multiButtonFillerWeight(rowSize = 1, perRow = 2, stretchLastRow = true))
        assertEquals(0f, multiButtonFillerWeight(rowSize = 2, perRow = 3, stretchLastRow = true))
    }

    @Test
    fun `no buttons means no rows`() {
        assertEquals(emptyList<Int>(), multiButtonRowPlan(childCount = 0, perRow = 2))
    }

    // ── content buried under the pinned bottom zone ────────────────────────────────────

    @Test
    fun `the reserve is the measured height of the bottom zone`() {
        // The bug: a fixed 80dp reserve under a ~140dp zone (two buttons + legal line), so the last
        // lines of content sat behind the buttons and the scroll range ended before reaching them.
        assertEquals(140.dp, bottomZoneReserve(hasBottomZone = true, measured = 140.dp))
        assertEquals(48.dp, bottomZoneReserve(hasBottomZone = true, measured = 48.dp))
    }

    @Test
    fun `before measurement it falls back to the previous fixed reserve`() {
        // First composition: keep the old frame rather than briefly reserving nothing.
        assertEquals(80.dp, bottomZoneReserve(hasBottomZone = true, measured = 0.dp))
    }

    @Test
    fun `no bottom zone reserves nothing`() {
        // What lets a short, centred screen stay centred.
        assertEquals(0.dp, bottomZoneReserve(hasBottomZone = false, measured = 0.dp))
        assertEquals(0.dp, bottomZoneReserve(hasBottomZone = false, measured = 140.dp))
    }
}
