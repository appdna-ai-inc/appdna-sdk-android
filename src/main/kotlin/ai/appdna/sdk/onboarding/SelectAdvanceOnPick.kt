package ai.appdna.sdk.onboarding

/**
 * Whether a pick in a Select moves the step on by itself (`auto_advance: true` on the block).
 *
 * A single-select advances on the pick. A multi-select advances once `field_config.max_selections`
 * options are picked, on the pick that reaches the limit: before it the user is not finished, and
 * with no limit there is no moment that could be called finished. A pick that opens the option's
 * bottom sheet does not advance, because the sheet is what the user is now looking at. The advance
 * itself is the step's own `next`, so required fields and next-step rules apply as they do to a CTA.
 *
 * Mirrors iOS `SelectAdvanceOnPick.swift` and the console's `selectAdvancesOnPick`. Pinned on both
 * platforms by the shared fixture `step_advance/select_advance_on_pick`.
 */
internal object SelectAdvanceOnPick {

    /** Long enough to draw the picked option as selected before the screen changes. */
    const val DELAY_MS = 250L

    /**
     * [before] and [after] are the selection either side of the pick, in values. A refused or
     * removing tap leaves a multi-select no longer than it was, which is why both are needed.
     */
    fun shouldAdvance(block: ContentBlock, before: List<String>, after: List<String>, opensSheet: Boolean): Boolean {
        if (block.auto_advance != true || opensSheet) return false
        val isMulti = block.multi_select == true || (block.field_config?.get("multi_select") as? Boolean) == true
        if (!isMulti) return !after.firstOrNull().isNullOrEmpty()
        val limit = (block.field_config?.get("max_selections") as? Number)?.toDouble() ?: return false
        if (limit < 1) return false
        return after.size > before.size && after.size >= limit
    }

    /** The question asked before the advance, when the author asked for one. */
    data class Confirmation(val text: String, val yes: String, val no: String)

    /**
     * `field_config.advance_confirm: true` on a Select that advances: the pick that would advance asks first, with
     * `advance_confirm_text` (default "Are you sure?"), a confirm button `advance_confirm_yes` (default "Yes") that
     * advances and a cancel button `advance_confirm_no` (default "No") that stays with the pick kept. Without
     * `auto_advance` there is nothing to confirm. Pinned by the shared fixture `step_advance/select_advance_confirm`.
     */
    fun confirmation(block: ContentBlock): Confirmation? {
        if (block.auto_advance != true || block.field_config?.get("advance_confirm") as? Boolean != true) return null
        fun text(key: String, fallback: String) = (block.field_config?.get(key) as? String)?.takeIf { it.isNotBlank() } ?: fallback
        return Confirmation(
            text("advance_confirm_text", "Are you sure?"),
            text("advance_confirm_yes", "Yes"),
            text("advance_confirm_no", "No"),
        )
    }
}
