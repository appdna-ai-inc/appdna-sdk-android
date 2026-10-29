package ai.appdna.sdk.onboarding

/**
 * The options a question step's stored answer chose, so a user who comes back to an answered
 * question sees it answered. Mirrors iOS `QuestionStepView.restoredSelection`.
 */
internal object QuestionSelection {

    /**
     * The option ids in [saved]`["selected"]`, in the order they were stored: only ids [config]
     * still offers, each once, and only the first for a single-select question. A stored answer
     * read back after process death carries a list, the same as one kept in memory.
     */
    fun restored(saved: Map<String, Any>?, config: StepConfig): List<String> {
        val raw: List<String> = when (val v = saved?.get("selected")) {
            is List<*> -> v.filterIsInstance<String>()
            is String -> listOf(v)
            else -> emptyList()
        }
        val offered = config.options.orEmpty().map { it.id }.toSet()
        val kept = raw.filter { it in offered }.distinct()
        return if (config.selection_mode == SelectionMode.MULTI) kept else kept.take(1)
    }
}
