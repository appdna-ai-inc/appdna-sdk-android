package ai.appdna.sdk.core

/**
 * What a BARE ICON STRING on a paywall item means — #1067.
 *
 * 🔴 A NAME IS NOT AN EMOJI AND IS NOT A VECTOR NAME. The console writes `icon: "check"` on a
 * features item, and before this each platform guessed differently and both guessed wrong:
 *
 *  * this platform drew the string as TEXT, so a user read the word **"check"** five times down
 *    the page where the feature list should be;
 *  * iOS drew `Image(systemName: "check")` — not an SF Symbol (`checkmark` is) — so nothing drew
 *    and the icon vanished silently.
 *
 * `resolveIcon` cannot be used directly here: given a bare `String` it answers `library = "emoji"`,
 * which is right for "✅" and is exactly the defect for "check".
 *
 * So a bare name is resolved against the mapping tables that already exist, in one place, and the
 * answer is an [IconReference] the shared [IconView] knows how to draw — including its documented
 * fallback ladder for a name nothing maps.
 *
 * Mirrors iOS `NamedIcon`.
 */
internal object NamedIcon {

    /**
     * An [IconReference] for a bare icon string, or null when there is nothing to draw.
     *
     * An emoji stays an emoji. A name either table knows becomes that library's reference. A name
     * NEITHER table knows still becomes a `lucide` reference rather than an emoji one, because
     * `IconView`'s lucide arm falls back to the emoji table and then to a visible placeholder —
     * whereas the emoji arm would print the name as prose, which is the defect.
     */
    fun reference(raw: String?, color: String? = null, size: Float? = null): IconReference? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (isEmojiGlyph(name)) return IconReference("emoji", name, color, size)
        if (LucideIconMapping.toMaterial.containsKey(name) || LucideIconMapping.toEmoji.containsKey(name)) {
            return IconReference("lucide", name, color, size)
        }
        if (MaterialIconMapping.resolve(name) != null || MaterialIconMapping.toEmoji.containsKey(name)) {
            return IconReference("material", name, color, size)
        }
        return IconReference("lucide", name, color, size)
    }
}
