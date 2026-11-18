package ai.appdna.sdk.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * `served_from` on a served onboarding flow, paywall, in-app message or survey (SPEC-536 D2).
 *
 * Present only while the item is serving ANOTHER item's content (Serve instead): [id] is the source
 * item, [version] the source's version that was copied. The server owns it; the SDK only copies it
 * onto events. Absent on every item that is not swapped, and on every config from a server older than
 * SPEC-536 P1.
 */
@Immutable
@androidx.annotation.Keep
data class ServedFrom(
    val id: String,
    val version: Int? = null,
) {
    internal companion object {
        /** A `served_from` map with a non-blank string `id`; anything else is absent (null). */
        fun parse(raw: Any?): ServedFrom? {
            val map = raw as? Map<*, *> ?: return null
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return ServedFrom(id = id, version = (map["version"] as? Number)?.toInt())
        }
    }
}

/**
 * The ONE place the content keys are written (SPEC-536 D1).
 *
 * Every event an onboarding flow, a paywall (and a purchase started from one), an in-app message from
 * the served set, or a survey emits carries the content that was on screen:
 *
 *  - `content_version` — the served item's `version`, the counter that moves only when the served copy
 *    changes. Absent only when the served item carries no version (a server older than SPEC-536 P1 for
 *    messages and surveys).
 *  - `content_source_id` — `served_from.id`, present ONLY while the item is serving another item's
 *    content, so analytics never blend a swapped item's numbers with its own.
 *
 * No event site writes these keys by hand: each one adds a surface's `contentStamp` (built by [of]) to its
 * properties LAST — `mapOf(...) + config.contentStamp`, or [apply] where the properties are not a literal —
 * so nothing a host supplies (paywall custom data, purchase metadata) can overwrite them. The `+` keeps the
 * literal `mapOf(` at the call site, which is what `check-event-name-parity`'s property leg reads.
 */
internal object ContentStamp {
    const val VERSION_KEY = "content_version"
    const val SOURCE_KEY = "content_source_id"

    /** The two keys; a host-supplied property with either name is dropped. */
    val KEYS: Set<String> = setOf(VERSION_KEY, SOURCE_KEY)

    /** The stamp for an item served with [version] and, while swapped, [servedFrom]. */
    fun of(version: Int?, servedFrom: ServedFrom?): Map<String, Any> = buildMap {
        version?.let { put(VERSION_KEY, it) }
        servedFrom?.let { put(SOURCE_KEY, it.id) }
    }

    /** [props] with [stamp] applied over it: the stamp wins any collision. */
    fun apply(props: Map<String, Any>, stamp: Map<String, Any>): Map<String, Any> =
        if (stamp.isEmpty()) props else props + stamp
}

/**
 * The stamp of the onboarding flow on screen, for the composables deep inside a step (chat, step sheets,
 * permission prompts, widget pins, remote validation) that only know the flow by its id. Provided by
 * `OnboardingFlowHost`; empty anywhere else (screens reuse step views and are not a SPEC-536 surface).
 */
internal val LocalContentStamp = staticCompositionLocalOf<Map<String, Any>> { emptyMap() }
