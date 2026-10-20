package ai.appdna.sdk.core

import kotlin.math.pow

/**
 * WHETHER Google's four-colour mark needs a white chip behind it. Mirror of iOS
 * `Core/GoogleMarkChip.swift`, and the same function both renderers call.
 *
 * 🔴 #1030 — the official mark is red, yellow, green and BLUE, and the Google provider's default
 * button fill is `#4285F4`. On that fill the blue arm and crossbar vanish into the button and the
 * mark reads as a "C". Google's own guidelines put the mark on a white surface — a white button,
 * or a white circular chip when the button is coloured — so it is off-guideline as well as
 * unreadable.
 *
 * A monochrome `icon_style` is an explicit author choice and keeps the letterform; an outlined or
 * minimal button has no fill, so the surface is the step's and the author owns it; a filled button
 * gets the chip when its own background is too dark to read a blue arm against.
 */
object GoogleMarkChip {

    /** Relative luminance at or above which a surface can carry the mark unaided. */
    const val LIGHT_ENOUGH: Double = 0.5

    /** `#RRGGBB` → WCAG relative luminance, or null when it is not a colour. */
    fun luminance(hex: String?): Double? {
        var h = hex?.trim() ?: return null
        if (h.isEmpty()) return null
        if (h.startsWith("#")) h = h.substring(1)
        if (h.length == 8) h = h.substring(0, 6)   // #RRGGBBAA — the alpha is not a colour
        if (h.length != 6) return null
        val v = h.toLongOrNull(16)?.toInt() ?: return null
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((v shr 16) and 0xFF)
        val g = channel((v shr 8) and 0xFF)
        val b = channel(v and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * @param buttonStyle `filled` | `outlined` | `minimal`
     * @param backgroundHex the button's RESOLVED background, authored or brand default
     * @param iconStyle the provider's `icon_style`, if any
     */
    fun isNeeded(buttonStyle: String?, backgroundHex: String?, iconStyle: String?): Boolean {
        if (iconStyle != null && iconStyle.startsWith("monochrome")) return false
        val style = buttonStyle ?: "filled"
        if (style == "outlined" || style == "minimal") return false
        // Nothing to measure means the brand default is what gets drawn, and that IS dark.
        val l = luminance(backgroundHex) ?: return true
        return l < LIGHT_ENOUGH
    }
}
