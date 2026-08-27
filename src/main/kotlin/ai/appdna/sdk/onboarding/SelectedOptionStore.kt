package ai.appdna.sdk.onboarding

/**
 * SPEC-448 §"The selected item" — what the user actually picked, addressable as
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
    }

    @Synchronized
    fun clear(fieldId: String) {
        storage.remove(fieldId)
    }

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
        return out
    }
}
