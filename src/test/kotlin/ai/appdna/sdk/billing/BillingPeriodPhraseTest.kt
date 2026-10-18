package ai.appdna.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #933 — the store's ISO period, phrased.
 *
 * 🔴 WHAT THIS IS REALLY GUARDING. The two stores emit `P1W` / `P1M` / `P3M` / `P1Y`, and the SDKs
 * have captured that since #923 while rendering none of it — the period beside a price was whatever
 * an author typed, and 0 of 81 production plans typed anything. So every assertion here is about a
 * string that currently reaches no screen, and the point of pinning them now is that iOS and
 * Android must produce the SAME words from the same input (Rule 8); `BillingPeriodPhraseTests.swift`
 * asserts the identical table.
 */
class BillingPeriodPhraseTest {

    @Test
    fun `the four shapes the stores actually emit parse`() {
        assertEquals(BillingPeriodPhrase.Parsed(1, BillingPeriodPhrase.PeriodUnit.WEEK), BillingPeriodPhrase.parse("P1W"))
        assertEquals(BillingPeriodPhrase.Parsed(1, BillingPeriodPhrase.PeriodUnit.MONTH), BillingPeriodPhrase.parse("P1M"))
        assertEquals(BillingPeriodPhrase.Parsed(3, BillingPeriodPhrase.PeriodUnit.MONTH), BillingPeriodPhrase.parse("P3M"))
        assertEquals(BillingPeriodPhrase.Parsed(1, BillingPeriodPhrase.PeriodUnit.YEAR), BillingPeriodPhrase.parse("P1Y"))
        assertEquals(BillingPeriodPhrase.Parsed(7, BillingPeriodPhrase.PeriodUnit.DAY), BillingPeriodPhrase.parse("P7D"))
    }

    @Test
    fun `a period we cannot name is left unrendered rather than guessed`() {
        // 🔴 Null, NOT a fallback string. A wrong period beside a real price is worse than no
        // period: it tells the user they are being charged on a schedule they are not.
        for (bad in listOf(null, "", "   ", "1M", "PT1H", "P0M", "P-1M", "P1", "MONTHLY", "P1M2W")) {
            assertNull("`$bad` must not parse", BillingPeriodPhrase.parse(bad))
            assertNull("`$bad` must not phrase", BillingPeriodPhrase.suffix(bad, "en"))
        }
    }

    @Test
    fun `a weekly plan says week, not seven days`() {
        /*
         * The reason this file does not use `java.time.Period`: `Period.parse("P1W")` yields 7 days
         * and loses the fact that a WEEKLY plan was sold. "/7 days" is wrong in every language.
         */
        assertEquals("/week", BillingPeriodPhrase.suffix("P1W", "en"))
        assertEquals("/7 days", BillingPeriodPhrase.suffix("P7D", "en"))
    }

    @Test
    fun `English singular and plural`() {
        assertEquals("/month", BillingPeriodPhrase.suffix("P1M", "en"))
        assertEquals("/3 months", BillingPeriodPhrase.suffix("P3M", "en"))
        assertEquals("/year", BillingPeriodPhrase.suffix("P1Y", "en"))
        assertEquals("1 month", BillingPeriodPhrase.duration("P1M", "en"))
        assertEquals("3 months", BillingPeriodPhrase.duration("P3M", "en"))
    }

    @Test
    fun `each of the console's ten locales phrases a monthly plan in its own language`() {
        // The set is the console's own paywall localizer (en, es, de, fr, ja, pt, ko, zh, it, ru),
        // so the SDK can phrase a period for every language an author can already localize into.
        val expected = mapOf(
            "en" to "/month", "es" to "/mes", "de" to "/Monat", "fr" to "/mois",
            "pt" to "/mês", "it" to "/mese", "ja" to "/月", "zh" to "/个月",
            "ko" to "/개월", "ru" to "/месяц",
        )
        for ((locale, phrase) in expected) {
            assertEquals("monthly in $locale", phrase, BillingPeriodPhrase.suffix("P1M", locale))
        }
    }

    @Test
    fun `an invariable plural is not pluralised anyway`() {
        // French `mois` does not take an -s; Italian and Spanish do. A table that appended `s`
        // mechanically would print "moiss".
        assertEquals("/3 mois", BillingPeriodPhrase.suffix("P3M", "fr"))
        assertEquals("/3 mesi", BillingPeriodPhrase.suffix("P3M", "it"))
        assertEquals("/3 meses", BillingPeriodPhrase.suffix("P3M", "es"))
    }

    @Test
    fun `a language with no plural inflection sets the unit against the number`() {
        assertEquals("/3月", BillingPeriodPhrase.suffix("P3M", "ja"))
        assertEquals("/3个月", BillingPeriodPhrase.suffix("P3M", "zh"))
        assertEquals("/3개월", BillingPeriodPhrase.suffix("P3M", "ko"))
    }

    @Test
    fun `Russian takes THREE forms, chosen by the last digits`() {
        /*
         * 🔴 The case a two-form table gets wrong, and the reason the table carries a third.
         * 1 → месяц, 2-4 → месяца, 5-20 → месяцев, and anything ending 11-14 → месяцев even
         * though it ends in 1-4. "5 месяца" is wrong in a way a reader notices at once.
         */
        assertEquals("/месяц", BillingPeriodPhrase.suffix("P1M", "ru"))
        assertEquals("/3 месяца", BillingPeriodPhrase.suffix("P3M", "ru"))
        assertEquals("/6 месяцев", BillingPeriodPhrase.suffix("P6M", "ru"))
        assertEquals("/12 месяцев", BillingPeriodPhrase.suffix("P12M", "ru"))
        // 11-14 are the trap: they end in 1-4 and still take the "many" form.
        assertEquals("/11 месяцев", BillingPeriodPhrase.suffix("P11M", "ru"))
        assertEquals("/21 месяц", BillingPeriodPhrase.suffix("P21M", "ru"))
        assertEquals("/22 месяца", BillingPeriodPhrase.suffix("P22M", "ru"))
        // `год` is irregular in the "many" form — `лет`, not `годов`.
        assertEquals("/год", BillingPeriodPhrase.suffix("P1Y", "ru"))
        assertEquals("/5 лет", BillingPeriodPhrase.suffix("P5Y", "ru"))
    }

    @Test
    fun `a region tag selects by language, and an unknown language reads English`() {
        assertEquals("/mês", BillingPeriodPhrase.suffix("P1M", "pt-BR"))
        assertEquals("/个月", BillingPeriodPhrase.suffix("P1M", "zh_Hans"))
        // 🔴 English, not null and not empty: an unlisted language must still read a period.
        assertEquals("/month", BillingPeriodPhrase.suffix("P1M", "sv"))
        assertEquals("/month", BillingPeriodPhrase.suffix("P1M", null))
        assertEquals("/month", BillingPeriodPhrase.suffix("P1M", ""))
    }
}

/**
 * #933 — the one place the table reaches a screen in this slice.
 *
 * `trial_duration` is author-supplied text, and both stores spell a trial period as an ISO
 * duration. A config carrying `P7D` — from an import, a hand-edited document or a generator, the
 * route #959's numeric price arrived by — used to render "P7D free trial" on a live paywall.
 */
class TrialLabelPhrasesAnIsoDurationTest {

    private fun plan(trialDuration: String?) = ai.appdna.sdk.paywalls.PaywallPlan(
        id = "p",
        product_id = "p",
        name = "Plan",
        price = "",
        trial_duration = trialDuration,
    )

    @Test
    fun `an ISO trial duration is phrased, not printed raw`() {
        assertEquals("7 days free trial", plan("P7D").trialLabel)
        assertEquals("1 week free trial", plan("P1W").trialLabel)
        assertEquals("1 month free trial", plan("P1M").trialLabel)
    }

    @Test
    fun `authored text that is not a period is untouched`() {
        // 🔴 The behaviour that must NOT change: every existing paywall authors prose here, and
        // phrasing it would be a silent rewrite of the author's words.
        assertEquals("7-day free trial", plan("7-day").trialLabel)
        assertEquals("one week free trial", plan("one week").trialLabel)
        assertNull(plan(null).trialLabel)
    }

    @Test
    fun `an authored trial label still wins verbatim`() {
        val p = ai.appdna.sdk.paywalls.PaywallPlan(
            id = "p", product_id = "p", name = "Plan", price = "",
            trial_duration = "P7D",
            trial = ai.appdna.sdk.paywalls.PaywallPlanTrial(label = "Free week"),
        )
        assertEquals("Free week", p.trialLabel)
    }
}
