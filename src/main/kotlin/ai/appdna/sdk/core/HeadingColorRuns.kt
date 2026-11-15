package ai.appdna.sdk.core

/**
 * Whether a heading's text carries a `[word](color:HEX)` run, and so is drawn through the markdown
 * parser rather than as plain text. Mirror of iOS `HeadingColorRuns`, pinned by the shared
 * `heading_color_runs_and_justify` fixture. Only a VALID run counts (6 or 8 hex digits); `color:` in any case, because an uppercase
 * `text_transform` is applied to the string before this reads it.
 */
object HeadingColorRuns {
    private val RUN = Regex("""\[[^\]\n]+\]\(color:(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})\)""", RegexOption.IGNORE_CASE)
    fun has(text: String): Boolean = RUN.containsMatchIn(text)
}
