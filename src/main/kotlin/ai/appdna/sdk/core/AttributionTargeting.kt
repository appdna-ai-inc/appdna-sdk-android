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

    private const val PREFS = "ai.appdna.attribution"
    private const val KEY_PAYLOAD = "payload"
    /** Set once a first-launch wait ran out with nothing delivered, so later launches do not wait again. */
    private const val KEY_WAIT_SPENT = "wait_spent"
    private const val MAX_RAW_KEYS = 200
    private const val MAX_VALUE_LENGTH = 1000

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    /** A payload stored before [attach] (setAttribution before configure), persisted once storage exists. */
    private var pendingPersist = false
    private val waiters = LinkedHashMap<Long, (Boolean) -> Unit>()
    private var nextWaiter = 0L

    /**
     * The stored payload: standard fields at the top level, raw parameters under `raw`. Null until one is set.
     * Setting it is memory-only (the fixtures use it); [store] is the host path.
     */
    @Volatile
    var payload: Map<String, Any?>? = null

    /** The standard fields in their stored (snake_case) spelling. A host may also pass the camelCase spelling. */
    val STANDARD_FIELDS = listOf(
        "network", "channel", "campaign", "campaign_id", "adset", "adset_id", "ad", "ad_id", "creative", "keyword",
        "placement", "site_id", "is_organic", "attribution_type", "install_country", "mmp",
    )

    private fun camel(snake: String): String =
        snake.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    /** A JSON scalar as stored: text (trimmed, capped, blank → null), a finite number, or a bool. Anything else null. */
    private fun scalar(v: Any?): Any? = when (v) {
        null, org.json.JSONObject.NULL -> null
        is String -> v.trim().takeIf { it.isNotEmpty() }?.take(MAX_VALUE_LENGTH)
        is Boolean -> v
        is Double -> v.takeIf { it.isFinite() }
        is Float -> v.takeIf { it.isFinite() }
        is Number -> v
        else -> null
    }

    /** `is_organic` from a bool, a 0 / 1 number, or "true" / "false" text (any case); anything else null. */
    private fun organic(v: Any?): Boolean? = when (val s = scalar(v)) {
        is Boolean -> s
        is Number -> when (s.toDouble()) { 1.0 -> true; 0.0 -> false; else -> null }
        is String -> when (s.lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null }
        else -> null
    }

    private fun text(n: Any): String = when (n) {
        is Double -> if (n % 1.0 == 0.0 && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()
        is Float -> if (n % 1f == 0f && kotlin.math.abs(n) < 1e7f) n.toLong().toString() else n.toString()
        else -> n.toString()
    }

    /**
     * What `setAttribution` stores, from what the host passed (shared fixtures `audience_eval/set_attribution_*`):
     *  - each standard field under its snake_case name, read from that name, its camelCase spelling or an alias
     *    (`source` / `media_source` → `network`, `provider` → `mmp`); text and numbers kept as text, blank absent;
     *  - `is_organic` as a bool (from a bool, 0 / 1 or "true" / "false");
     *  - `raw`: the host's `raw` map plus every top-level key that is not a standard field or an alias, JSON scalars
     *    only (nested values dropped), at most 200 keys. Mirrors iOS `AttributionTargeting.normalize`.
     */
    fun normalize(input: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val consumed = mutableSetOf("raw")
        for (field in STANDARD_FIELDS) {
            val keys = listOf(field, camel(field)) + (ALIASES[field] ?: emptyList())
            consumed += keys
            for (k in keys) {
                if (!input.containsKey(k)) continue
                val v = input[k]
                if (field == "is_organic") {
                    val b = organic(v)
                    if (b != null) { out[field] = b; break }
                } else {
                    val s = scalar(v)
                    if (s != null && s !is Boolean) { out[field] = if (s is String) s else text(s); break }
                }
            }
        }
        val raw = java.util.TreeMap<String, Any?>()
        val explicit = (input["raw"] as? Map<*, *>) ?: emptyMap<Any, Any>()
        for ((k, v) in explicit.entries.filter { it.key is String }.sortedBy { it.key as String }) {
            if (raw.size >= MAX_RAW_KEYS) break
            scalar(v)?.let { raw[k as String] = it }
        }
        for ((k, v) in input.entries.sortedBy { it.key }) {
            if (raw.size >= MAX_RAW_KEYS) break
            if (k in consumed || raw.containsKey(k)) continue
            scalar(v)?.let { raw[k] = it }
        }
        out["raw"] = raw
        return out
    }

    /** Storage, from configure(). Loads an earlier launch's payload, or persists one stored before configure. */
    fun attach(context: android.content.Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = p
            if (pendingPersist && payload != null) {
                persist(p, payload!!)
                pendingPersist = false
            } else if (payload == null) {
                p.getString(KEY_PAYLOAD, null)?.let { json ->
                    runCatching { payload = jsonToMap(org.json.JSONObject(json)) }
                }
            }
        }
    }

    private fun persist(p: android.content.SharedPreferences, value: Map<String, Any?>) {
        p.edit().putString(KEY_PAYLOAD, org.json.JSONObject(value).toString()).apply()
    }

    private fun jsonToMap(o: org.json.JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (k in o.keys()) {
            val v = o.get(k)
            m[k] = if (v is org.json.JSONObject) jsonToMap(v) else v
        }
        return m
    }

    /**
     * The host path: normalise, keep in memory, persist for the next launch (at configure when called before it),
     * and release every [whenReady] waiter with `true`. Returns the stored payload (the `attribution_set` properties).
     */
    fun store(input: Map<String, Any?>): Map<String, Any?> {
        val normalized = normalize(input)
        val released: List<(Boolean) -> Unit>
        synchronized(lock) {
            payload = normalized
            val p = prefs
            if (p != null) persist(p, normalized) else pendingPersist = true
            released = waiters.values.toList()
            waiters.clear()
        }
        released.forEach { it(true) }
        return normalized
    }

    /**
     * Calls back `true` as soon as attribution is stored (at once when an earlier call or launch stored it), or
     * `false` after [timeoutMs] when none arrived. A wait that runs out is remembered, so a later launch with still
     * nothing stored answers `false` at once: an organic install whose MMP never calls back waits on its first launch
     * only. `timeoutMs <= 0` answers at once. Mirrors iOS `AttributionTargeting.whenReady`.
     */
    fun whenReady(timeoutMs: Long, schedule: (Long, () -> Unit) -> Unit, completion: (Boolean) -> Unit) {
        if (payload != null) { completion(true); return }
        if (timeoutMs <= 0 || prefs?.getBoolean(KEY_WAIT_SPENT, false) == true) { completion(false); return }
        val id: Long
        synchronized(lock) {
            if (payload != null) { completion(true); return }
            id = nextWaiter++
            waiters[id] = completion
        }
        schedule(timeoutMs) {
            val w = synchronized(lock) { waiters.remove(id) } ?: return@schedule
            prefs?.edit()?.putBoolean(KEY_WAIT_SPENT, true)?.apply()
            w(false)
        }
    }

    /** Test seam: a relaunch — memory and the storage handle are gone, storage is kept, configure attaches again. */
    internal fun relaunchForTest(context: android.content.Context) {
        synchronized(lock) { payload = null; prefs = null; pendingPersist = false; waiters.clear() }
        attach(context)
    }

    /** Test seam: forget memory, the persisted copy and the storage handle. */
    internal fun resetForTest() {
        synchronized(lock) {
            prefs?.edit()?.clear()?.apply()
            prefs = null
            payload = null
            pendingPersist = false
            waiters.clear()
        }
    }

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
