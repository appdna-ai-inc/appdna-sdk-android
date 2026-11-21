package ai.appdna.sdk.core

/**
 * audience rules on the install's attribution, on every surface that evaluates [AudienceRuleSet].
 *
 * An attribution rule is a trait-shaped condition whose key names the attribution instead of a trait:
 *   - `attribution.<field>`: a standard field — `network`, `channel`, `campaign`, `campaign_id`, `adset`, `adset_id`,
 *     `ad`, `ad_id`, `creative`, `keyword`, `placement`, `site_id`, `is_organic`, `attribution_type`,
 *     `install_country`, `mmp`;
 *   - `attribution.param.<key>`: any raw parameter the MMP delivered (`af_sub1`, `deep_link_value`, …).
 *
 * The value comes from the attribution the app handed the SDK, never from the trait map, so a trait called
 * `attribution.network` cannot spoof it. With no attribution stored every attribution rule reads "no value", so
 * `eq` / `in` / `contains` / `starts_with` fail closed and only `not_exists` matches. Text compares case-insensitively:
 * MMPs disagree on case ("Facebook Ads" / "facebook ads"), and a rule must not depend on which one sent it.
 */
internal object AttributionTargeting {
    const val PREFIX = "attribution."
    private const val PARAM = "param."

    /** Standard field → the payload keys that may carry it, first non-blank wins. `source` is the host's spelling. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "network" to listOf("network", "source", "media_source"),
        "is_organic" to listOf("is_organic", "isOrganic"),
        "mmp" to listOf("mmp", "provider"),
    )

    /** The stored payload: standard fields at the top level, raw parameters under `raw`. Null until one is set. */
    @Volatile
    var payload: Map<String, Any?>? = null

    fun isAttributionKey(key: String?): Boolean = key != null && key.startsWith(PREFIX)

    /** The value an attribution rule reads, or null when nothing was delivered for it. */
    fun resolve(key: String): Any? {
        val p = payload ?: return null
        val field = key.removePrefix(PREFIX)
        if (field.startsWith(PARAM)) {
            val raw = p["raw"] as? Map<*, *> ?: return null
            return raw[field.removePrefix(PARAM)].takeUnless { it == null || it == org.json.JSONObject.NULL || it == "" }
        }
        for (k in ALIASES[field] ?: listOf(field)) {
            val v = p[k]
            if (v != null && v != org.json.JSONObject.NULL && v != "") return v
        }
        return null
    }

    /** Text folded for a case-insensitive comparison; a list folds element-wise; anything else is unchanged. */
    fun fold(value: Any?): Any? = when (value) {
        is String -> value.lowercase()
        is List<*> -> value.map { if (it is String) it.lowercase() else it }
        else -> value
    }
}
