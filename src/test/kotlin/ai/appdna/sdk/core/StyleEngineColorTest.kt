package ai.appdna.sdk.core

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Verifies the 8-char hex byte order matches iOS RGBA
 * (`Paywalls/PaywallHelperViews.swift` `Color(hex:)`), not Compose's
 * default ARGB long form.
 *
 * Also covers 6-char RGB, 3/4-char shorthand, "transparent"/"clear" named
 * colors, missing `#` prefix, and invalid-input fallback (transparent —
 * matching iOS `(0, 0, 0, 0)` default branch).
 */
class StyleEngineColorTest {

    /**
     * Compose `Color(r,g,b,a)` stores components as 0..1 floats. Round-trip
     * back to 0..255 ints to compare against the source bytes exactly.
     */
    private fun Color.toRgbaInts(): IntArray {
        return intArrayOf(
            (red * 255f).roundToInt(),
            (green * 255f).roundToInt(),
            (blue * 255f).roundToInt(),
            (alpha * 255f).roundToInt(),
        )
    }

    private fun assertRgba(color: Color, r: Int, g: Int, b: Int, a: Int) {
        val components = color.toRgbaInts()
        assertEquals("R channel", r, components[0])
        assertEquals("G channel", g, components[1])
        assertEquals("B channel", b, components[2])
        assertEquals("A channel", a, components[3])
    }

    // -----------------------------------------------------------------------
    // 8-char hex — RGBA (the actual bug this fixes)
    // -----------------------------------------------------------------------

    @Test
    fun `8-char hex parses as RGBA matching iOS`() {
        // Before an earlier fix, Color(colorLong) treated the long as ARGB, producing
        // (A=0x11, R=0x22, G=0x33, B=0x4F) — channels rotated, near-transparent.
        // Correct iOS-equivalent decode: R=0x11, G=0x22, B=0x33, A=0x4F.
        assertRgba(StyleEngine.parseColor("#1122334F"), r = 0x11, g = 0x22, b = 0x33, a = 0x4F)
    }

    @Test
    fun `8-char hex full opacity`() {
        assertRgba(StyleEngine.parseColor("#AABBCCFF"), r = 0xAA, g = 0xBB, b = 0xCC, a = 0xFF)
    }

    @Test
    fun `8-char hex fully transparent`() {
        assertRgba(StyleEngine.parseColor("#11223300"), r = 0x11, g = 0x22, b = 0x33, a = 0x00)
    }

    // -----------------------------------------------------------------------
    // 6-char hex — RGB, alpha defaults to 0xFF
    // -----------------------------------------------------------------------

    @Test
    fun `6-char hex defaults alpha to full opacity`() {
        assertRgba(StyleEngine.parseColor("#112233"), r = 0x11, g = 0x22, b = 0x33, a = 0xFF)
    }

    @Test
    fun `6-char hex without prefix`() {
        assertRgba(StyleEngine.parseColor("AABBCC"), r = 0xAA, g = 0xBB, b = 0xCC, a = 0xFF)
    }

    // -----------------------------------------------------------------------
    // Shorthand expansion (3 / 4 char)
    // -----------------------------------------------------------------------

    @Test
    fun `4-char shorthand returns transparent matching iOS`() {
        // R64 reverted shorthand expansion to match iOS
        // PaywallHelperViews.swift:81-87 (`count == 6 || count == 8`
        // only). 3/4-char shorthand falls through to Color.Transparent.
        // Android previously expanded `#1234` → `#11223344`, producing
        // visible color on Android but transparent on iOS for identical
        // payloads.
        assertRgba(StyleEngine.parseColor("#1234"), r = 0, g = 0, b = 0, a = 0)
    }

    @Test
    fun `3-char shorthand returns transparent matching iOS`() {
        // Same fix as 4-char above; #1A3 returns
        // Color.Transparent matching iOS.
        assertRgba(StyleEngine.parseColor("#1A3"), r = 0, g = 0, b = 0, a = 0)
    }

    // -----------------------------------------------------------------------
    // Named colors + invalid input
    // -----------------------------------------------------------------------

    @Test
    fun `transparent named color returns Color Transparent`() {
        assertRgba(StyleEngine.parseColor("transparent"), r = 0, g = 0, b = 0, a = 0)
    }

    @Test
    fun `clear named color returns Color Transparent`() {
        assertRgba(StyleEngine.parseColor("clear"), r = 0, g = 0, b = 0, a = 0)
    }

    @Test
    fun `invalid input falls back to transparent matching iOS`() {
        // iOS default branch in Color(hex:) is `(0, 0, 0, 0)` (transparent),
        // not black — see Paywalls/PaywallHelperViews.swift.
        assertRgba(StyleEngine.parseColor("#GGHHII"), r = 0, g = 0, b = 0, a = 0)
        assertRgba(StyleEngine.parseColor("not-a-color"), r = 0, g = 0, b = 0, a = 0)
        assertRgba(StyleEngine.parseColor(""), r = 0, g = 0, b = 0, a = 0)
        assertRgba(StyleEngine.parseColor("#12"), r = 0, g = 0, b = 0, a = 0)
    }

    @Test
    fun `prefix is optional`() {
        // Same value with and without `#` must produce identical color.
        val withHash = StyleEngine.parseColor("#1122334F")
        val withoutHash = StyleEngine.parseColor("1122334F")
        assertEquals(withHash.toRgbaInts().toList(), withoutHash.toRgbaInts().toList())
    }
}
