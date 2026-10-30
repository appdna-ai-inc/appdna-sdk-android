package ai.appdna.sdk.billing

/**
 * The STORE's billing period, as words a user can read.
 *
 * Both stores report a subscription's period structurally — StoreKit's `subscription.subscriptionPeriod`,
 * Play's `billingPeriod` — as an ISO-8601 duration: `P1W`, `P1M`, `P3M`, `P1Y`. The SDKs have
 * captured it since paywalls began showing store prices ([PriceResolver]) and rendered NONE of it: the period beside a price was
 * whatever an author typed in `plan.period`, which 0 of 81 production plans carried. So a paywall
 * showed the store's real price with no period at all, or with a period that nobody updated when
 * the product changed.
 *
 * 🔴 A STORE LOCALIZES A PRICE, NOT A PHRASE. `$4.49` arrives already formatted for the user's
 * region, because the store did it. "per month" does not — no store returns it — so the words are
 * ours, which is why this table exists at all rather than a formatter call. (Owner's decision,
 * 2026-10-18: a built-in table in each SDK, rather than a console-authored template.)
 *
 * 🔴 WHY NOT `java.time.Period` / `Duration`: neither parses the mixed week form (`P1W`) the way
 * these stores emit it — `Period.parse("P1W")` yields 7 days, losing the fact that the author sold
 * a WEEKLY plan, and a "/7 days" suffix where "/week" belongs is wrong in every language. The
 * parsing here is deliberately literal about the unit the store named.
 *
 * The English forms are the fallback for every locale this table does not carry, so an unlisted
 * language degrades to a readable English period rather than to an empty string.
 */
internal object BillingPeriodPhrase {

    internal enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

    internal data class Parsed(val count: Int, val unit: PeriodUnit)

    /**
     * `P1M` → 1 MONTH. Returns null for anything that is not one of the four shapes the two stores
     * actually emit, because a period we cannot name is better left unrendered than guessed at.
     */
    /**
     * Compiled once. `parse` is reached from `trialLabel`, which a plan card evaluates on every
     * recomposition — building a `Regex` per call would compile the same pattern on every frame.
     */
    private val ISO = Regex("^P(\\d+)([DWMY])$")

    internal fun parse(iso: String?): Parsed? {
        val s = iso?.trim()?.uppercase() ?: return null
        val m = ISO.find(s) ?: return null
        val count = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val unit = when (m.groupValues[2]) {
            "D" -> PeriodUnit.DAY
            "W" -> PeriodUnit.WEEK
            "M" -> PeriodUnit.MONTH
            else -> PeriodUnit.YEAR
        }
        return Parsed(count, unit)
    }

    /**
     * The period as it reads BESIDE A PRICE: `/month`, `/3 months`.
     *
     * The leading separator is part of the locale's entry, because it is not a slash everywhere —
     * Japanese and Chinese set the unit directly against the price.
     */
    fun suffix(iso: String?, locale: String?): String? {
        val p = parse(iso) ?: return null
        val t = table(locale)
        return if (p.count == 1) t.suffixOne(p.unit) else t.suffixMany(p.count, p.unit)
    }

    /**
     * The period as a STANDALONE duration: `7 days`, `1 month`. Used by the trial and
     * introductory-offer phrases, where the period is a noun rather than a price suffix.
     */
    fun duration(iso: String?, locale: String?): String? {
        val p = parse(iso) ?: return null
        return table(locale).duration(p.count, p.unit)
    }

    // ── the table ────────────────────────────────────────────────────────────────────────────────

    private fun table(locale: String?): Forms {
        // A tag may arrive as `pt-BR` or `zh_Hans`; only the language subtag selects a table.
        val lang = locale?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() }
        return TABLES[lang] ?: EN
    }

    /**
     * One language's forms.
     *
     * `one` / `many` are the unit's singular and plural nouns; `sep` is what sits between a price
     * and the unit. A language with no plural inflection (ja, ko, zh) sets `many == one`, which is
     * not a shortcut — it is the correct grammar, and writing it explicitly keeps the table honest
     * about which languages were considered rather than defaulted.
     */
    private class Forms(
        val sep: String,
        val one: Map<PeriodUnit, String>,
        val many: Map<PeriodUnit, String>,
        /** Chooses the plural form for `count`; only Russian needs more than "not one". */
        val plural: (Int, PeriodUnit, Forms) -> String = { n, u, f -> "$n ${f.many[u]}" },
    ) {
        fun suffixOne(u: PeriodUnit) = "$sep${one[u]}"
        fun suffixMany(n: Int, u: PeriodUnit) = "$sep${plural(n, u, this)}"
        fun duration(n: Int, u: PeriodUnit) = if (n == 1) "1 ${one[u]}" else plural(n, u, this)
    }

    private val EN = Forms(
        sep = "/",
        one = mapOf(PeriodUnit.DAY to "day", PeriodUnit.WEEK to "week", PeriodUnit.MONTH to "month", PeriodUnit.YEAR to "year"),
        many = mapOf(PeriodUnit.DAY to "days", PeriodUnit.WEEK to "weeks", PeriodUnit.MONTH to "months", PeriodUnit.YEAR to "years"),
    )

    /**
     * The ten languages the console's own paywall localizer offers
     * (`LocalizationEditor.tsx`: en, es, de, fr, ja, pt, ko, zh, it, ru), so the SDK can phrase a
     * period for every language an author can already localize a paywall into — and no more,
     * because a table entry nobody can check is worse than the English fallback.
     */
    private val TABLES: Map<String, Forms> = mapOf(
        "en" to EN,
        "es" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "día", PeriodUnit.WEEK to "semana", PeriodUnit.MONTH to "mes", PeriodUnit.YEAR to "año"),
            many = mapOf(PeriodUnit.DAY to "días", PeriodUnit.WEEK to "semanas", PeriodUnit.MONTH to "meses", PeriodUnit.YEAR to "años"),
        ),
        "de" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "Tag", PeriodUnit.WEEK to "Woche", PeriodUnit.MONTH to "Monat", PeriodUnit.YEAR to "Jahr"),
            many = mapOf(PeriodUnit.DAY to "Tage", PeriodUnit.WEEK to "Wochen", PeriodUnit.MONTH to "Monate", PeriodUnit.YEAR to "Jahre"),
        ),
        "fr" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "jour", PeriodUnit.WEEK to "semaine", PeriodUnit.MONTH to "mois", PeriodUnit.YEAR to "an"),
            // `mois` is invariable in the plural; `ans` is not.
            many = mapOf(PeriodUnit.DAY to "jours", PeriodUnit.WEEK to "semaines", PeriodUnit.MONTH to "mois", PeriodUnit.YEAR to "ans"),
        ),
        "pt" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "dia", PeriodUnit.WEEK to "semana", PeriodUnit.MONTH to "mês", PeriodUnit.YEAR to "ano"),
            many = mapOf(PeriodUnit.DAY to "dias", PeriodUnit.WEEK to "semanas", PeriodUnit.MONTH to "meses", PeriodUnit.YEAR to "anos"),
        ),
        "it" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "giorno", PeriodUnit.WEEK to "settimana", PeriodUnit.MONTH to "mese", PeriodUnit.YEAR to "anno"),
            many = mapOf(PeriodUnit.DAY to "giorni", PeriodUnit.WEEK to "settimane", PeriodUnit.MONTH to "mesi", PeriodUnit.YEAR to "anni"),
        ),
        // No plural inflection on the unit, and no space before it.
        "ja" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "日", PeriodUnit.WEEK to "週", PeriodUnit.MONTH to "月", PeriodUnit.YEAR to "年"),
            many = mapOf(PeriodUnit.DAY to "日", PeriodUnit.WEEK to "週", PeriodUnit.MONTH to "月", PeriodUnit.YEAR to "年"),
            plural = { n, u, f -> "$n${f.many[u]}" },
        ),
        "zh" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "天", PeriodUnit.WEEK to "周", PeriodUnit.MONTH to "个月", PeriodUnit.YEAR to "年"),
            many = mapOf(PeriodUnit.DAY to "天", PeriodUnit.WEEK to "周", PeriodUnit.MONTH to "个月", PeriodUnit.YEAR to "年"),
            plural = { n, u, f -> "$n${f.many[u]}" },
        ),
        "ko" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "일", PeriodUnit.WEEK to "주", PeriodUnit.MONTH to "개월", PeriodUnit.YEAR to "년"),
            many = mapOf(PeriodUnit.DAY to "일", PeriodUnit.WEEK to "주", PeriodUnit.MONTH to "개월", PeriodUnit.YEAR to "년"),
            plural = { n, u, f -> "$n${f.many[u]}" },
        ),
        /*
         * 🔴 Russian needs THREE forms, not two, and the rule is by the last digits rather than the
         * magnitude: 1, 21, 31 take the singular; 2-4, 22-24 take the "few" form; 0, 5-20 and
         * anything ending 11-14 take the "many" form. A two-form table would print "5 месяца",
         * which is wrong in a way a reader notices immediately.
         *
         * `many` here holds the FEW form, so the shared two-form path stays usable; the `plural`
         * lambda below reaches the third.
         */
        "ru" to Forms(
            sep = "/",
            one = mapOf(PeriodUnit.DAY to "день", PeriodUnit.WEEK to "неделя", PeriodUnit.MONTH to "месяц", PeriodUnit.YEAR to "год"),
            many = mapOf(PeriodUnit.DAY to "дня", PeriodUnit.WEEK to "недели", PeriodUnit.MONTH to "месяца", PeriodUnit.YEAR to "года"),
            plural = { n, u, _ -> "$n ${russianForm(n, u)}" },
        ),
    )

    private val RU_FEW = mapOf(PeriodUnit.DAY to "дня", PeriodUnit.WEEK to "недели", PeriodUnit.MONTH to "месяца", PeriodUnit.YEAR to "года")
    private val RU_MANY = mapOf(PeriodUnit.DAY to "дней", PeriodUnit.WEEK to "недель", PeriodUnit.MONTH to "месяцев", PeriodUnit.YEAR to "лет")
    private val RU_ONE = mapOf(PeriodUnit.DAY to "день", PeriodUnit.WEEK to "неделя", PeriodUnit.MONTH to "месяц", PeriodUnit.YEAR to "год")

    /** The Russian 1 / few / many selection, by last digits. */
    private fun russianForm(n: Int, u: PeriodUnit): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> RU_MANY[u]!!
            mod10 == 1 -> RU_ONE[u]!!
            mod10 in 2..4 -> RU_FEW[u]!!
            else -> RU_MANY[u]!!
        }
    }
}
