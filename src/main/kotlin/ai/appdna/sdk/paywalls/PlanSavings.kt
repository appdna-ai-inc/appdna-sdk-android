package ai.appdna.sdk.paywalls

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.floor

/**
 * A plan's savings claim, computed from the prices the paywall DRAWS.
 *
 * A badge reading "save 52%" used to be literal text worked out from the AUTHORED prices. Once the
 * paywall began drawing the STORE's prices, the figure no longer matched the prices beside it — it
 * understated or overstated the saving depending on which way the store price moved, which is the
 * kind of pricing claim store review and consumer-protection rules flag.
 *
 * So a plan's `badge` or `savings_text` may carry the token `{{savings_percent}}`, filled here:
 *
 *  1. Each plan's price PER MONTH — a store price over the store's ISO period, an authored price over
 *     the unit written right after its amount ("$5.00/mo"), else the period its own words name (`period`, label, price text, product id); each falls back to
 *     the other.
 *  2. The anchor is the most expensive per-month plan in the same list; a plan's saving is
 *     `1 − perMonth / anchor`, FLOORED to a whole percent, so the claim never rounds up.
 *  3. HIDDEN RATHER THAN WRONG: a text carrying the token is dropped whole when its saving cannot be
 *     known — fewer than two comparable plans, an unreadable amount or period, a saving under 1%, or
 *     a list that mixes store and authored prices (two currencies cannot be compared).
 *  4. Text without the token is the author's own words and is drawn exactly as written.
 *
 * Mirrors iOS `PlanSavings.swift` and the console's `plan-savings.ts`. Pinned on all three by the
 * shared fixture `billing/plan_savings_percent`.
 */
internal object PlanSavings {

    const val TOKEN = "{{savings_percent}}"
    private val TOKEN_RE = Regex("""\{\{\s*savings_percent\s*\}\}""")
    private val AMOUNT_RE = Regex("""\d(?:[\d.,'   ]*\d)?""")

    fun hasToken(text: String?): Boolean = text != null && TOKEN_RE.containsMatchIn(text)

    /** The text to draw, or null to draw nothing. Without the token, untouched; with it, a percent is required. */
    fun fill(text: String?, percent: Int?): String? {
        if (text == null) return null
        if (!hasToken(text)) return text
        if (percent == null) return null
        return TOKEN_RE.replace(text, Regex.escapeReplacement("$percent%"))
    }

    /** The number in an authored price string: "$39.99", "39,99 €", "1.299,00 kr", "¥1,200". */
    fun parseAmount(text: String?): Double? {
        val m = text?.let { AMOUNT_RE.find(it) } ?: return null
        var t = m.value.filterNot { it == '\'' || it == ' ' || it == ' ' || it == ' ' }
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        if (lastDot >= 0 && lastComma >= 0) {
            val dec = if (lastDot > lastComma) '.' else ','
            val group = if (dec == '.') ',' else '.'
            t = t.filterNot { it == group }.replace(dec, '.')
        } else if (lastDot >= 0 || lastComma >= 0) {
            val sep = if (lastDot >= 0) '.' else ','
            val count = t.count { it == sep }
            val after = t.length - t.lastIndexOf(sep) - 1
            t = if (count == 1 && after != 3) t.replace(sep, '.') else t.filterNot { it == sep }
        }
        return t.toDoubleOrNull()
    }

    /** Months in an ISO-8601 billing period (`P1M`, `P1Y`, `P1W`, `P7D`). */
    fun monthsFromIso(iso: String?): Double? {
        val m = Regex("""^P(\d+)([DWMY])$""").find(iso?.trim()?.uppercase() ?: return null) ?: return null
        val n = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val v = n.toDouble()
        return when (m.groupValues[2]) {
            "Y" -> 12 * v
            "M" -> v
            "W" -> (12.0 / 52.0) * v
            else -> if (n % 7 == 0) (12.0 / 52.0) * (v / 7) else (12.0 / 365.0) * v
        }
    }

    private val PRICE_UNIT_RE = Regex("""^\s*(?:(?:\p{Sc}|\p{L}{1,3}\.?)\s*)?(?:/|per\b|a\b|an\b|each\b|every\b)\s*(?:1\s*)?([A-Za-z]+)""", RegexOption.IGNORE_CASE)

    /**
     * Months in the unit written RIGHT AFTER a price's amount: "$5.00/mo", "$4.99 a week", "$59.99 per
     * year". An authored annual plan's price is often a MONTHLY figure ("$5.00/mo, billed yearly"); read over the
     * plan's twelve months it claimed "save 97%" against $19.99/mo instead of 74%. The unit beside the amount
     * describes the amount. null when no unit follows it.
     */
    fun monthsFromPriceUnit(text: String?): Double? {
        val m = text?.let { AMOUNT_RE.find(it) } ?: return null
        val unit = PRICE_UNIT_RE.find(text.substring(m.range.last + 1))?.groupValues?.get(1)?.lowercase() ?: return null
        return when (unit) {
            "mo", "mos", "mon", "mth", "month", "months" -> 1.0
            "yr", "yrs", "year", "years", "annum" -> 12.0
            "wk", "wks", "week", "weeks" -> 12.0 / 52.0
            "quarter", "qtr" -> 3.0
            "day", "days" -> 12.0 / 365.0
            else -> null
        }
    }

    /** Months a plan's own WORDS name. */
    fun monthsFromWords(vararg parts: String?): Double? {
        val words = " " + parts.filterNotNull().joinToString(" ").lowercase()
            .replace(Regex("""[_.\-/]"""), " ") + " "
        fun has(p: String) = Regex(p).containsMatchIn(words)
        return when {
            has("""\b12 ?months?\b""") -> 12.0
            has("""\b(year|years|yearly|annual|annually|yr|yrs)\b""") -> 12.0
            has("""\b(6 ?months?|semi ?annual|semiannual|biannual|half ?year)\b""") -> 6.0
            has("""\b(quarter|quarterly|3 ?months?)\b""") -> 3.0
            has("""\b(week|weeks|weekly|wk)\b""") -> 12.0 / 52.0
            has("""\b(month|monthly|mo)\b""") -> 1.0
            else -> null
        }
    }

    /** Each plan's saving as a whole percent, or null where it cannot be known. */
    fun percents(plans: List<PaywallPlan>): List<Int?> {
        val rows = plans.map { p ->
            val store = p.priceSource == "store"
            val authored = p.price_display.ifBlank { p.price }
            val words = monthsFromWords(p.period, p.label.ifBlank { p.name }, authored, p.product_id)
            val amount = if (store) p.resolvedStoreAmount else parseAmount(authored)
            val months = if (store) (monthsFromIso(p.resolvedStorePeriod) ?: words)
                else (monthsFromPriceUnit(authored) ?: words ?: monthsFromIso(p.resolvedStorePeriod))
            val perMonth = if (amount != null && amount > 0 && months != null && months > 0) amount / months else null
            perMonth to store
        }
        val known = rows.filter { it.first != null }
        if (known.size < 2 || known.map { it.second }.toSet().size != 1) return plans.map { null }
        val anchor = known.maxOf { it.first!! }
        return rows.map { (v, _) ->
            if (v == null) null
            else floor((1 - v / anchor) * 100 + 1e-6).toInt().takeIf { it >= 1 }
        }
    }

    /** The plan list with every token filled, and every text whose figure cannot be known removed. */
    fun apply(plans: List<PaywallPlan>): List<PaywallPlan> {
        if (plans.none { hasToken(it.badge) || hasToken(it.savings_text) }) return plans
        val pct = percents(plans)
        return plans.mapIndexed { i, p ->
            p.copy(
                resolvedSavingsPercent = pct[i],
                badge = fill(p.badge, pct[i]),
                savings_text = fill(p.savings_text, pct[i]),
            )
        }
    }
}

/**
 * A copy with every `{{savings_percent}}` filled from the prices each plan list DRAWS, and every text
 * whose figure cannot be known removed ([PlanSavings]). Run AFTER the store substitutions.
 *
 * The plan-text localizations (`plan.<i>.badge`, `plan.<i>.savings`) are filled too, against the list
 * the renderer draws in the order it draws it — a translated "Spare {{savings_percent}}" would
 * otherwise reach the screen with the token in it.
 */
internal fun PaywallConfig.applyingSavings(): PaywallConfig {
    fun saved(list: List<PaywallPlan>?): ImmutableList<PaywallPlan>? = list?.let { PlanSavings.apply(it).toImmutableList() }
    var out = copy(
        plans = saved(plans),
        sections = sections.map { section ->
            val d = section.data
            val sectionPlans = d?.plans
            if (section.type != "plans" || d == null || sectionPlans == null) section
            else section.copy(data = d.copy(plans = saved(sectionPlans)))
        }.toImmutableList(),
    )
    val locs = localizations ?: return out
    if (locs.values.none { t -> t.values.any { PlanSavings.hasToken(it) } }) return out
    val sectionPlans = sections.firstOrNull { it.type == "plans" }?.data?.plans ?: emptyList()
    val drawn = PlanSavings.percents(PaywallPlanOrder.sorted(if (sectionPlans.isEmpty()) (plans ?: emptyList()) else sectionPlans))
    out = out.copy(localizations = locs.mapValues { (_, table) ->
        table.mapNotNull { (key, value) ->
            if (!PlanSavings.hasToken(value)) return@mapNotNull key to value
            val parts = key.split(".")
            val pct = if (parts.size == 3 && parts[0] == "plan" && (parts[2] == "badge" || parts[2] == "savings"))
                parts[1].toIntOrNull()?.let { drawn.getOrNull(it) } else null
            // Unfillable: the key is dropped, so the plan's own (already filled, or hidden) text stands.
            PlanSavings.fill(value, pct)?.let { key to it }
        }.toMap()
    })
    return out
}

/** A copy with the store's NUMERIC price on every plan, wherever it lives — the same walk as [applyingStorePrices]. */
internal fun PaywallConfig.applyingStoreAmounts(amounts: Map<String, Double>): PaywallConfig {
    if (amounts.isEmpty()) return this
    fun valued(list: List<PaywallPlan>?): ImmutableList<PaywallPlan>? =
        list?.map { it.copy(resolvedStoreAmount = amounts[it.product_id]) }?.toImmutableList()
    return copy(
        plans = valued(plans),
        sections = sections.map { section ->
            val d = section.data
            val sectionPlans = d?.plans
            if (section.type != "plans" || d == null || sectionPlans == null) section
            else section.copy(data = d.copy(plans = valued(sectionPlans)))
        }.toImmutableList(),
    )
}

/**
 * Everything the store answered, applied the way the presenter applies it: price, period, trial,
 * numeric amount, and THEN the savings figure over the prices that result. One function so the
 * presenter and the tests that drive its output cannot drift apart. An empty map still fills
 * `{{savings_percent}}`, from the authored prices that will be drawn.
 */
internal fun PaywallConfig.applyingStoreFacts(
    facts: Map<String, ai.appdna.sdk.billing.StorePriceLookup.StoreFacts>,
): PaywallConfig =
    applyingStorePrices(facts.mapNotNull { (id, f) -> f.price?.let { id to it } }.toMap())
        .applyingStorePeriods(facts.mapNotNull { (id, f) -> f.periodIso?.let { id to it } }.toMap())
        .applyingStoreTrials(facts.mapNotNull { (id, f) -> f.trialIso?.let { id to it } }.toMap())
        .applyingStoreAmounts(facts.mapNotNull { (id, f) -> f.amount?.let { id to it } }.toMap())
        .applyingSavings()
