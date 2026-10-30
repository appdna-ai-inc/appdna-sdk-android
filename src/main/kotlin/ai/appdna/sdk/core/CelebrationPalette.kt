package ai.appdna.sdk.core

/*
 * 🔴 IT LIVES IN `core/`, BESIDE THE OVERLAY THAT DRAWS IT, and that is deliberate.
 *
 * `check:authorability` sweeps the block, paywall and screen renderers asking "could an author
 * have set this colour?", and a seven-hex fallback list answers no — correctly, and uselessly:
 * the author's control is `particle_effect.colors`, one call away, and the list is its DEFAULT.
 * Putting the rule under `onboarding/` made the sweep read 12 of these as controls nobody can
 * reach. The particle overlay has always lived in `core/` for the same reason — it is not a block.
 */

/**
 * WHICH COLOURS a celebration draws when a document authors none. Mirror of iOS
 * `Onboarding/OnboardingCelebration.swift`, and the same list both overlays read.
 *
 * 🔴 The two overlays disagreed and nothing could see it. iOS returned SwiftUI's semantic
 * colours (`.red` is #FF3B30) and this platform returned Compose's named constants (`Color.Red` is
 * #FF0000) for four of six, so every `fireworks` and every plain `confetti` with no authored
 * `colors` — the default the editor produces — drew pure RGB here and Apple's palette there. Four
 * of seven values differed on the commonest type.
 *
 * An earlier pass on this file had already committed to the HIG values in its own comment and
 * converted only the three EXPLICIT hexes, leaving the four named constants. So the values are now
 * written out and `celebration_palette` asserts the two platforms produce identical lists, rather
 * than two comments being trusted to agree.
 */
object CelebrationPalette {

    /** The palette for a type, as `#RRGGBB` strings. */
    fun fallback(type: String?): List<String> = when (type ?: "confetti") {
        "hearts" -> listOf("#FF3B30", "#FF2D55", "#FF6B9D")
        "snow" -> listOf("#FFFFFF", "#F2F2F2", "#E6E6E6")
        "sparkle" -> listOf("#FFCC00", "#FF9500", "#FFD700")
        "fireworks" -> listOf("#FF3B30", "#007AFF", "#34C759", "#FFCC00", "#AF52DE", "#FF9500")
        else -> listOf("#FF3B30", "#007AFF", "#34C759", "#FFCC00", "#AF52DE", "#FF9500", "#FF2D55")
    }

    /** What a celebration actually draws: the authored colours, or the type's fallback. */
    fun colors(type: String?, authored: List<String>?): List<String> {
        if (!authored.isNullOrEmpty()) return authored
        return fallback(type)
    }

    /**
     * The glyph a type draws, or `""` for a filled circle.
     *
     * 🔴 The two platforms branched on different questions. iOS asked `type == "confetti"` and
     * drew a circle, else a glyph — so an UNKNOWN type (a document from a newer console, a typo)
     * drew a bullet CHARACTER. This platform asked whether its glyph table had an entry and drew
     * a circle otherwise. Neither answer is wrong; having two is. Unknown falls back to the
     * circle, which is what `confetti` — the default — already draws.
     */
    fun glyph(type: String?): String = when (type ?: "confetti") {
        "hearts" -> "\u2764\uFE0F"
        "snow" -> "\u2744\uFE0F"
        "sparkle" -> "\u2728"
        "fireworks" -> "\uD83C\uDF86"
        else -> ""
    }
}
