package ai.appdna.sdk.onboarding

/**
 * How much a two-column summary tile's value shrinks so its longest word fits the tile.
 *
 * A value wraps on word boundaries on its own; only a word wider than the tile broke mid-word. Shrinking
 * the whole value until that word fits keeps it whole, and the floor stops a long id reaching an
 * unreadable size. Past the floor the word breaks, which reads better than vanishing. iOS `SummaryTileFit`.
 */
internal object SummaryTileFit {
    /** The smallest a value is drawn, against its authored size. */
    const val MIN_SCALE = 0.5f

    /** Headroom, so a width rounded differently by the text layout cannot still break the word. */
    private const val MARGIN = 0.97f

    /** The longest word's width at the authored size, and the tile's inner width, in the same unit. */
    fun scale(longestWordWidth: Float, availableWidth: Float): Float {
        if (longestWordWidth <= 0f || availableWidth <= 0f || longestWordWidth <= availableWidth) return 1f
        return (availableWidth * MARGIN / longestWordWidth).coerceIn(MIN_SCALE, 1f)
    }
}
