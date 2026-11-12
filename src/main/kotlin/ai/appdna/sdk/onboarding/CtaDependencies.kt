package ai.appdna.sdk.onboarding

/**
 * A button that stays inactive until named conditions on this screen hold.
 *
 * `field_config.cta_dependencies` on a `button` is a list of `{ block_id, condition, count?, value? }`. Every entry
 * must hold for the button to be active; until then a tap does nothing, and it draws in `cta_disabled_bg_color` (or
 * its own colour at 40% opacity when none is set), and once they hold in `cta_enabled_bg_color` (or its own colour).
 *
 * A condition reads the answer of the block it names (`field_id`, else its id), anywhere on the step: `answered`
 * (something picked, typed or switched on), `all_selected` (every authored option picked), `min_selected` (at least
 * `count`, default 1), `correct` (right by that Select's `correct_values`; answered when none are marked) and `equals`
 * (the answer is, or includes, `value`). An entry naming a block that is not on the step, or a condition this build
 * does not know, holds: a button must never be left dead by a reference the user cannot satisfy.
 *
 * Mirrors iOS `CtaDependencies.swift` and the console's `ctaDependenciesMet`. Pinned on both platforms by the shared
 * fixture `step_advance/cta_dependencies`.
 */
internal object CtaDependencies {

    /** The opacity of an inactive button that has no authored inactive colour. */
    const val INACTIVE_OPACITY = 0.4f

    private fun entries(button: ContentBlock): List<Map<*, *>> =
        (button.field_config?.get("cta_dependencies") as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .filter { (it["block_id"] as? String)?.isNotEmpty() == true }

    /** Whether the button has any dependency at all (an unset or empty list: the button behaves as it always has). */
    fun has(button: ContentBlock): Boolean = entries(button).isNotEmpty()

    /** The block with [id] at any depth (`children`, `stack_children`). */
    fun find(blocks: List<ContentBlock>?, id: String): ContentBlock? {
        for (b in blocks.orEmpty()) {
            if (b.id == id) return b
            (find(b.children, id) ?: find(b.stack_children, id))?.let { return it }
        }
        return null
    }

    /** Whether the button is active: it has no dependencies, or every one holds. */
    fun met(button: ContentBlock, blocks: List<ContentBlock>, inputValues: Map<String, Any?>): Boolean =
        entries(button).all { holds(it, blocks, inputValues) }

    private fun holds(dep: Map<*, *>, blocks: List<ContentBlock>, inputValues: Map<String, Any?>): Boolean {
        val target = find(blocks, dep["block_id"] as String) ?: return true
        val picked = SelectAnswerFeedback.selection(inputValues[target.field_id ?: target.id])
        return when (dep["condition"] as? String) {
            "answered" -> picked.isNotEmpty()
            "all_selected" -> {
                val options = target.field_options.orEmpty().map { it.value }.filter { it.isNotEmpty() }
                if (options.isEmpty()) picked.isNotEmpty() else options.all { it in picked }
            }
            "min_selected" -> {
                val raw = (dep["count"] as? Number)?.toDouble() ?: 1.0
                val n = if (raw.isFinite()) maxOf(1, kotlin.math.floor(raw).toInt()) else 1
                picked.size >= n
            }
            "correct" -> {
                if (picked.isEmpty()) {
                    false
                } else {
                    // Nothing marked: answered is all that can be asked. Marked but unfinished: not yet.
                    SelectAnswerFeedback.message(target, picked)?.isCorrect
                        ?: SelectAnswerFeedback.correctValues(target).isEmpty()
                }
            }
            "equals" -> (dep["value"] as? String)?.takeIf { it.isNotEmpty() }?.let { it in picked } ?: false
            else -> true
        }
    }
}
