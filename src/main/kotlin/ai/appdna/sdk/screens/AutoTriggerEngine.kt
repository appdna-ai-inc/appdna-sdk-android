package ai.appdna.sdk.screens

import ai.appdna.sdk.core.AudienceRuleEvaluator
import java.util.*

/**
 * Where a screen trigger's impression counts and last-shown times are kept between launches. They were in
 * memory only, so `max_impressions` and `cooldown_hours` started again on every cold launch: a screen capped at one
 * impression showed once per launch. `once_per_session` stays in memory, since a new process is a new session.
 */
internal interface ScreenFrequencyStore {
    /** Null while the store cannot be read yet (no application context); asked again on the next decision. */
    fun load(): Pair<Map<String, Int>, Map<String, Long>>?
    fun save(counts: Map<String, Int>, lastShown: Map<String, Long>)
}

/** The production store: one SharedPreferences file, read once per process. No context yet = nothing persisted. */
internal object PrefsScreenFrequencyStore : ScreenFrequencyStore {
    private const val PREFS = "ai.appdna.sdk.screen_freq"
    private fun prefs() = ai.appdna.sdk.AppDNA.getApplicationContext()
        ?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    override fun load(): Pair<Map<String, Int>, Map<String, Long>>? {
        val p = prefs() ?: return null
        val counts = mutableMapOf<String, Int>()
        val last = mutableMapOf<String, Long>()
        for ((k, v) in p.all) {
            when {
                k.startsWith("count:") && v is Int -> counts[k.removePrefix("count:")] = v
                k.startsWith("last:") && v is Long -> last[k.removePrefix("last:")] = v
            }
        }
        return counts to last
    }

    override fun save(counts: Map<String, Int>, lastShown: Map<String, Long>) {
        val p = prefs() ?: return
        val e = p.edit()
        for ((id, n) in counts) e.putInt("count:$id", n)
        for ((id, t) in lastShown) e.putLong("last:$id", t)
        e.apply()
    }
}

internal class AutoTriggerEngine(private val store: ScreenFrequencyStore? = null) {
    private val impressionCounts = mutableMapOf<String, Int>()
    private val lastShownTimes = mutableMapOf<String, Long>()
    private val shownThisSession = mutableSetOf<String>()
    private var loaded = false

    fun resetSession() { shownThisSession.clear() }

    /** Read the persisted counts once, the first time the engine decides (the store may need a context first). */
    private fun ensureLoaded() {
        if (loaded || store == null) return
        val (counts, last) = store.load() ?: return
        loaded = true
        for ((k, v) in counts) impressionCounts[k] = maxOf(v, impressionCounts[k] ?: 0)
        for ((k, v) in last) lastShownTimes[k] = maxOf(v, lastShownTimes[k] ?: 0L)
    }

    fun evaluate(
        entries: List<ScreenIndexEntry>,
        event: String,
        properties: Map<String, Any>?,
        userTraits: Map<String, Any>,
        sessionCount: Int,
        daysSinceInstall: Int?,
        currentScreenName: String?,
    ): String? {
        val now = System.currentTimeMillis()
        ensureLoaded()

        val matching = entries
            .filter { evaluateEntry(it, event, properties, userTraits, sessionCount, daysSinceInstall, currentScreenName, now) }
            .sortedByDescending { it.priority ?: 0 }

        val best = matching.firstOrNull() ?: return null

        impressionCounts[best.id] = (impressionCounts[best.id] ?: 0) + 1
        lastShownTimes[best.id] = now
        shownThisSession.add(best.id)
        store?.save(mapOf(best.id to impressionCounts.getValue(best.id)), mapOf(best.id to now))
        return best.id
    }

    private fun evaluateEntry(
        entry: ScreenIndexEntry, event: String, properties: Map<String, Any>?,
        userTraits: Map<String, Any>, sessionCount: Int, daysSinceInstall: Int?,
        currentScreenName: String?, now: Long,
    ): Boolean {
        // Check scheduling
        // An unparsable or zone-less date is no constraint (iOS: `ISO8601.date(from:)` is nil → skipped).
        entry.startDate?.let { parseISODate(it) }?.let { if (it > now) return false }
        entry.endDate?.let { parseISODate(it) }?.let { if (it < now) return false }

        // Check audience
        if (entry.audienceRules != null && !AudienceRuleEvaluator.evaluate(entry.audienceRules, userTraits)) return false

        val rules = entry.triggerRules ?: return false

        // Check frequency
        rules.frequency?.let { freq ->
            freq.maxImpressions?.let { if ((impressionCounts[entry.id] ?: 0) >= it) return false }
            if (freq.oncePerSession == true && entry.id in shownThisSession) return false
            freq.cooldownHours?.let { hours ->
                lastShownTimes[entry.id]?.let { lastTime ->
                    if (now - lastTime < hours * 3600_000L) return false
                }
            }
        }

        var matched = false

        // Event triggers
        rules.events?.forEach { trigger ->
            if (trigger.eventName == event) {
                // Each condition is evaluated by its OPERATOR, with the evaluator the user-trait conditions
                // use (it compared everything by equality, so "gt 10" meant "== 10"). An event tracked
                // without properties is evaluated on none; it no longer fires a conditioned trigger outright.
                val props = properties.orEmpty()
                if (trigger.conditions.isNullOrEmpty() || trigger.conditions.all { c ->
                        AudienceRuleEvaluator.evaluateRule(
                            ai.appdna.sdk.core.AudienceRule(trait = c.field, operator = c.operator, value = c.value), props,
                        )
                    }
                ) { matched = true }
            }
        }

        // Session count
        rules.sessionCount?.let { s ->
            s.exact?.let { if (sessionCount == it) matched = true }
            if (s.exact == null) {
                val minOk = s.min == null || sessionCount >= s.min
                val maxOk = s.max == null || sessionCount <= s.max
                if (minOk && maxOk && (s.min != null || s.max != null)) matched = true
            }
        }

        // Days since install
        // An unknown install time matches no install-age rule.
        if (daysSinceInstall != null) rules.daysSinceInstall?.let { t ->
            val minOk = t.min == null || daysSinceInstall >= t.min
            val maxOk = t.max == null || daysSinceInstall <= t.max
            if (minOk && maxOk && (t.min != null || t.max != null)) matched = true
        }

        // Screen-based
        rules.onScreen?.let { pattern ->
            currentScreenName?.let { name ->
                if (matchGlob(pattern, name)) matched = true
            }
        }

        // User traits
        rules.userTraits?.let { traits ->
            if (traits.isNotEmpty() && traits.all { c ->
                // Evaluated as an audience rule: every operator the console offers, a stored null read as
                // not set, and an unknown operator failing closed (it used to fire for every user).
                AudienceRuleEvaluator.evaluateRule(
                    ai.appdna.sdk.core.AudienceRule(trait = c.trait, operator = c.operator, value = c.value), userTraits,
                )
            }) matched = true
        }

        return matched
    }

    private fun matchGlob(pattern: String, string: String): Boolean {
        val p = pattern.lowercase(); val s = string.lowercase()
        if (!p.contains("*")) return p == s
        if (p.startsWith("*") && p.endsWith("*")) return s.contains(p.drop(1).dropLast(1))
        if (p.startsWith("*")) return s.endsWith(p.drop(1))
        if (p.endsWith("*")) return s.startsWith(p.dropLast(1))
        return p == s
    }

    /**
     * ISO-8601 instant WITH its zone (`Z` or an offset), as iOS
     * `ISO8601.date(from:)`; null otherwise. The zone-less `SimpleDateFormat` fallback this used to
     * have read a zone-less value in the device's time zone, and returned 0 for anything else — so a
     * bad `end_date` blocked the screen for ever (0 < now) where iOS shows it.
     */
    internal fun parseISODate(iso: String): Long? = ai.appdna.sdk.core.IsoInstant.parseEpochMs(iso)
}
