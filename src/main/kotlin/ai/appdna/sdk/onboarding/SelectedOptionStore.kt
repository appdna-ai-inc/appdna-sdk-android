package ai.appdna.sdk.onboarding

/**
 * "The selected item" — what the user actually picked, addressable as
 * `{{selected.<field_id>.subtitle}}` on a later screen. Mirrors iOS `SelectedOptionStore`.
 *
 * 🔴 Deliberately NOT stored in `responses`, and that is an acceptance criterion rather than a
 * design preference: `responses` is what customer webhooks receive, and quietly fattening every
 * payload with the full option object — images, sheet blocks, translations — would change the
 * bytes every existing integration parses. A customer who never asked for this must see
 * byte-identical payloads.
 *
 * The reported VALUE is unchanged, so analytics joins and next_step_rules keep working exactly as
 * before; the rest of the option lives here, reachable only through the template root.
 */
internal object SelectedOptionStore {

    private val storage = mutableMapOf<String, Any>()

    /** Record a single-select choice. */
    @Synchronized
    fun record(fieldId: String, option: InputOption) {
        storage[fieldId] = payload(option)
        revision.intValue++
    }

    /**
     * Record a multi-select choice.
     *
     * A LIST in selection order, not a set: the order the user picked in is information, and
     * `{{selected.x.0.label}}` addressing the first choice depends on it being stable.
     */
    @Synchronized
    fun record(fieldId: String, options: List<InputOption>) {
        storage[fieldId] = options.map { payload(it) }
        revision.intValue++
    }

    @Synchronized
    fun clear(fieldId: String) {
        storage.remove(fieldId)
        revision.intValue++
    }

    /**
     * The presented flow's own options, by field id, as `{ value, label }` records. An answer with no
     * recorded pick (a resumed flow, a default, a host patch) still draws its label from these.
     */
    private var flowOptionRecords: Map<String, List<Map<String, Any>>> = emptyMap()

    /** Keep this flow's option list. Replaces the previous flow's: only the flow on screen has answers to draw. */
    @Synchronized
    fun setFlowOptions(flow: OnboardingFlowConfig) {
        flowOptionRecords = optionRecords(flow)
        revision.intValue++
    }

    /** The flow's option records, for the raw pass (`flow_options`). */
    @Synchronized
    fun flowOptions(): Map<String, Any?> = flowOptionRecords

    /** What a drawn answer looks up: each field's picked records, then the flow's own options. */
    @Synchronized
    fun drawingRecords(): Map<String, Any?> = drawingRecords(storage, flowOptionRecords)

    /** Picks first, so a recorded pick wins over the flow's list. */
    internal fun drawingRecords(selected: Map<String, Any?>, flowOptions: Map<String, Any?>): Map<String, Any?> {
        val out = selected.toMutableMap()
        for ((field, list) in flowOptions) {
            val picks: List<Any?> = when (val p = selected[field]) { null -> emptyList(); is List<*> -> p; else -> listOf(p) }
            out[field] = picks + ((list as? List<*>) ?: emptyList<Any?>())
        }
        return out
    }

    /**
     * `{ value, label }` for every option a step of this flow authors. A label that is still a template
     * has nothing to draw until it resolves, so it is left out and the stored value is drawn.
     */
    internal fun optionRecords(flow: OnboardingFlowConfig): Map<String, List<Map<String, Any>>> {
        val out = mutableMapOf<String, MutableList<Map<String, Any>>>()
        fun add(field: String?, value: String?, label: String?) {
            if (field.isNullOrEmpty() || value.isNullOrEmpty() || value.contains("{{")) return
            if (label.isNullOrBlank() || label.contains("{{")) return
            out.getOrPut(field) { mutableListOf() }.add(mapOf("value" to value, "label" to label))
        }
        fun walk(blocks: List<ContentBlock>?) {
            for (block in blocks.orEmpty()) {
                if (block.type.startsWith("input_")) {
                    for (option in block.field_options.orEmpty()) add(block.field_id, option.value ?: option.id, option.label)
                }
                walk(block.children)
                walk(block.stack_children)
            }
        }
        for (step in flow.steps) {
            walk(step.config.content_blocks)
            for (field in step.config.fields.orEmpty()) {
                for (option in field.options.orEmpty()) add(field.id, HostDataResolver.stringifyTemplateValue(option.value) ?: option.id, option.label)
            }
        }
        return out
    }

    /**
     * Keep only the recorded options whose value is still rendered (a multi-select
     * whose list shrank after host data arrived). Empty → the field is cleared.
     */
    @Synchronized
    fun retainValues(fieldId: String, values: Set<String>) {
        val current = storage[fieldId] as? List<*> ?: return
        val kept = current.filter { (it as? Map<*, *>)?.get("value")?.toString() in values }
        if (kept.isEmpty()) storage.remove(fieldId) else storage[fieldId] = kept
        revision.intValue++
    }

    /**
     * Bumps on every write, so a step whose blocks read the `selected` root
     * re-resolves (Compose snapshot state: a read subscribes).
     */
    val revision = androidx.compose.runtime.mutableIntStateOf(0)

    /** The map the template resolver reads as the `selected` root. */
    @Synchronized
    fun snapshot(): Map<String, Any> = storage.toMap()

    /**
     * Test seam: inject an already-shaped payload. The fixture describes what the user PICKED, not
     * the option DTO that produced it, so this takes the flattened shape directly.
     */
    @Synchronized
    fun seedForTesting(fieldId: String, value: Any) {
        storage[fieldId] = value
    }

    @Synchronized
    fun resetForTesting() {
        storage.clear()
        flowOptionRecords = emptyMap()
    }

    /** Test seam: the flow's option list in its stored shape, without building a whole flow. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun seedFlowOptionsForTesting(records: Map<String, Any?>) {
        flowOptionRecords = records as Map<String, List<Map<String, Any>>>
    }

    /**
     * Flatten an option into something a dot path can walk.
     *
     * Only fields an author would plausibly reference. Copying the whole DTO would put images and
     * nested sheet blocks behind `{{selected.…}}`, and a template resolving to a map renders as
     * its toString — worse than not resolving at all.
     */
    private fun payload(option: InputOption): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        (option.value ?: option.id)?.let { out["value"] = it }
        option.label?.let { out["label"] = it }
        option.subtitle?.let { out["subtitle"] = it }
        option.category?.let { out["category"] = it }
        option.image_url?.let { out["image_url"] = it }
        option.icon?.let { out["icon"] = it }
        // The keys whose text is a Google answer's content, each with that text as the user typed it:
        // a later step that DRAWS one draws Google's attribution ([GoogleCredit]); one that STORES or
        // SENDS it (an option value, a field default, a link or media URL) takes the typed text.
        option.googleKeys?.takeIf { it.isNotEmpty() }?.let { out[GOOGLE_KEYS_KEY] = it }
        return out
    }

    /** Where a recorded option keeps its Google keys ([InputOption.googleKeys]): key → typed text. */
    const val GOOGLE_KEYS_KEY = "google_keys"

    /**
     * The text a picked option draws, or null: no label, a blank one, or a label that is a Google
     * answer's content, which a token drawing an answer does not credit. iOS `drawnLabel`.
     */
    internal fun drawnLabel(record: Any?): String? {
        if ("label" in googleKeys(record)) return null
        return HostDataResolver.stringifyTemplateValue((record as? Map<*, *>)?.get("label"))?.takeIf { it.isNotBlank() }
    }

    /**
     * A drawn answer as what the user picked: a select stores the option's id, and the screen
     * must show its label. [field] is the answer's field id, [answer] the stored value (a single pick,
     * or a multi pick's list, whose labels are comma-joined). [selected] is [drawingRecords], so it holds
     * picks then the flow's own options. Null when nothing here applies, and the caller draws the stored
     * value: no record for this value anywhere. A multi pick keeps the stored value of any item with no label.
     * Never for a value that leaves the screen: an option's `value`, a URL, a hook's headers.
     */
    internal fun drawnAnswer(field: String, answer: Any?, selected: Map<String, Any?>): String? {
        val records = when (val r = selected[field]) { is List<*> -> r; null -> emptyList<Any?>(); else -> listOf(r) }
        fun labelFor(text: String): String? = records.firstNotNullOfOrNull { r ->
            val v = HostDataResolver.stringifyTemplateValue((r as? Map<*, *>)?.get("value"))
            if (v == text) drawnLabel(r) else null
        }
        if (answer is List<*>) {
            if (answer.isEmpty()) return null
            val texts = answer.map { item -> HostDataResolver.stringifyTemplateValue(item) ?: return null }
            return texts.joinToString(", ") { labelFor(it) ?: it }
        }
        return HostDataResolver.stringifyTemplateValue(answer)?.let(::labelFor)
    }

    /** [drawnAnswer] for a `responses.` or `step.` path, the only roots that name an answer. */
    internal fun drawnAnswerAt(path: String, answer: Any?, selected: Map<String, Any?>): String? =
        if (path.startsWith("responses.") || path.startsWith("step.")) drawnAnswer(path.substringAfterLast('.'), answer, selected) else null

    /** The recorded option's keys whose text is a Google answer's content. */
    fun googleKeys(record: Any?): List<String> =
        ((record as? Map<*, *>)?.get(GOOGLE_KEYS_KEY) as? Map<*, *>)?.keys?.mapNotNull { it as? String }?.sorted().orEmpty()

    /** The recorded option's text for [key] with every Google answer as the user typed it; null when [key] is not a Google key. */
    fun typedText(record: Any?, key: String): String? =
        ((record as? Map<*, *>)?.get(GOOGLE_KEYS_KEY) as? Map<*, *>)?.get(key) as? String

    /**
     * A token that stops at a picked record (`{{selected.stop}}`), or at a multi pick's list of them,
     * where a Google answer must not go (a stored value, a URL, a surface with no credit): each record
     * with every key that came from a Google answer replaced by its text as the user typed it. Anything
     * that is not a record is returned as it is.
     */
    fun withTypedText(value: Any?): Any? = when (value) {
        is List<*> -> value.map { withTypedText(it) }
        is Map<*, *> -> {
            val keys = value[GOOGLE_KEYS_KEY] as? Map<*, *>
            if (keys.isNullOrEmpty()) value
            else LinkedHashMap<Any?, Any?>(value).apply {
                for ((k, typed) in keys) if (containsKey(k)) put(k, typed)
            }
        }
        else -> value
    }
}
