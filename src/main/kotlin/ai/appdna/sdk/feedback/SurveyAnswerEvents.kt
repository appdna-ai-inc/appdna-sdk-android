package ai.appdna.sdk.feedback

/**
 * When `survey_question_answered` is tracked: once per question answered, not once per change.
 *
 * A typed answer is tracked when the user moves on from the question (Next or Submit), with what they typed, and only
 * if it is not blank. A tap answer (NPS, choice, rating, …) is tracked when it is picked, as before. Either way an
 * answer equal to the one already tracked for that question is not tracked again. Mirrors iOS `SurveyAnswerEvents`;
 * shared fixture `survey_question_answered_once`.
 */
internal class SurveyAnswerEvents {
    data class Emit(val questionId: String, val type: String, val answer: Any)

    private val tracked = mutableMapOf<String, Any>()
    private val pending = mutableMapOf<String, Pair<String, Any>>()

    /** The answer to [questionId] changed. Returns the event to track now, or null. */
    fun changed(questionId: String, type: String, answer: Any): Emit? {
        if (type in TYPED) {
            pending[questionId] = type to answer
            return null
        }
        return emitIfNew(questionId, type, answer)
    }

    /** The user moved on from [questionId] (Next or Submit). Returns the typed answer's event, or null. */
    fun leaving(questionId: String): Emit? {
        val (type, answer) = pending.remove(questionId) ?: return null
        if (answer is String && answer.isBlank()) return null
        return emitIfNew(questionId, type, answer)
    }

    private fun emitIfNew(questionId: String, type: String, answer: Any): Emit? {
        if (tracked[questionId] == answer) return null
        tracked[questionId] = answer
        return Emit(questionId, type, answer)
    }

    companion object {
        /** Question types answered by typing. */
        val TYPED = setOf("free_text")
    }
}
