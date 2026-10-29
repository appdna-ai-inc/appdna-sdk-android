package ai.appdna.sdk.screens

import ai.appdna.sdk.core.AudienceRuleEvaluator
import java.util.*

internal class AutoTriggerEngine {
    private val impressionCounts = mutableMapOf<String, Int>()
    private val lastShownTimes = mutableMapOf<String, Long>()
    private val shownThisSession = mutableSetOf<String>()

    fun resetSession() { shownThisSession.clear() }

    fun evaluate(
        entries: List<ScreenIndexEntry>,
        event: String,
        properties: Map<String, Any>?,
        userTraits: Map<String, Any>,
        sessionCount: Int,
        daysSinceInstall: Int,
        currentScreenName: String?,
    ): String? {
        val now = System.currentTimeMillis()

        val matching = entries
            .filter { evaluateEntry(it, event, properties, userTraits, sessionCount, daysSinceInstall, currentScreenName, now) }
            .sortedByDescending { it.priority ?: 0 }

        val best = matching.firstOrNull() ?: return null

        impressionCounts[best.id] = (impressionCounts[best.id] ?: 0) + 1
        lastShownTimes[best.id] = now
        shownThisSession.add(best.id)
        return best.id
    }

    private fun evaluateEntry(
        entry: ScreenIndexEntry, event: String, properties: Map<String, Any>?,
        userTraits: Map<String, Any>, sessionCount: Int, daysSinceInstall: Int,
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
        rules.daysSinceInstall?.let { t ->
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
