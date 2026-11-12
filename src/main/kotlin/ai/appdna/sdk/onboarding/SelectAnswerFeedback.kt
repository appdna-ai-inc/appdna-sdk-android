package ai.appdna.sdk.onboarding

/**
 * A Select that tells the user whether their pick was right.
 *
 * The author marks the correct option(s) in `field_config.correct_values`. Once the user has answered, a message
 * shows under the options: `feedback_correct_text` in `feedback_correct_color` for a right answer,
 * `feedback_wrong_text` in `feedback_wrong_color` for a wrong one. A single-select is judged on its pick. A
 * multi-select is judged once as many options are picked as there are correct ones, and is right only when the
 * picked set IS the correct set; before that the user has not finished answering and nothing shows.
 *
 * Mirrors iOS `SelectAnswerFeedback.swift` and the console's `selectAnswerFeedback`. Pinned on both platforms by
 * the shared fixture `dto_parsing/select_answer_feedback`.
 */
internal object SelectAnswerFeedback {

    data class Message(val isCorrect: Boolean, val text: String, val colorHex: String)

    const val DEFAULT_CORRECT_TEXT = "Correct!"
    const val DEFAULT_WRONG_TEXT = "Not quite."
    const val DEFAULT_CORRECT_COLOR = "#16A34A"
    const val DEFAULT_WRONG_COLOR = "#DC2626"

    /**
     * A stored or authored value as the string an option's value is compared with. A whole number is written
     * without a fraction, so an authored `2` matches the option `"2"`; a checked toggle is `"true"`.
     */
    fun valueString(any: Any?): String? = when (any) {
        is String -> any.ifEmpty { null }
        is Boolean -> if (any) "true" else null
        is Number -> {
            val d = any.toDouble()
            when {
                d.isNaN() || d.isInfinite() -> null
                d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
                else -> d.toString()
            }
        }
        else -> null
    }

    /** The authored correct option values, deduplicated in order; empty when the feature is off. */
    fun correctValues(block: ContentBlock): List<String> {
        val raw = block.field_config?.get("correct_values")
        val list: List<Any?> = when (raw) {
            is List<*> -> raw
            null -> emptyList()
            else -> listOf(raw)
        }
        return list.mapNotNull(::valueString).distinct()
    }

    /** What a stored answer picked, in values. */
    fun selection(stored: Any?): List<String> =
        if (stored is List<*>) stored.mapNotNull(::valueString) else listOfNotNull(valueString(stored))

    private fun text(block: ContentBlock, key: String, fallback: String): String =
        (block.field_config?.get(key) as? String)?.takeIf { it.isNotBlank() } ?: fallback

    /** The message for [selection] (what the user has picked, in values), or null when nothing is to be shown. */
    fun message(block: ContentBlock, selection: List<String>): Message? {
        val correct = correctValues(block)
        val picked = selection.filter { it.isNotEmpty() }
        if (correct.isEmpty() || picked.isEmpty()) return null
        val isMulti = block.multi_select == true || (block.field_config?.get("multi_select") as? Boolean) == true
        val isRight = if (isMulti) {
            val set = picked.toSet()
            if (set.size < correct.size) return null
            set == correct.toSet()
        } else {
            picked.first() in correct
        }
        return if (isRight) {
            Message(true, text(block, "feedback_correct_text", DEFAULT_CORRECT_TEXT), text(block, "feedback_correct_color", DEFAULT_CORRECT_COLOR))
        } else {
            Message(false, text(block, "feedback_wrong_text", DEFAULT_WRONG_TEXT), text(block, "feedback_wrong_color", DEFAULT_WRONG_COLOR))
        }
    }
}
