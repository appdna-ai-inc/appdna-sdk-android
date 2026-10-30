package ai.appdna.sdk.paywalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The STORE's billing period reaches the screen, and an authored one still wins.
 *
 * Both stores report a subscription's period structurally and the SDKs have captured it since store prices were first looked up
 * ([ai.appdna.sdk.billing.PriceResolver]) while rendering none of it: the period beside a price was
 * whatever an author typed, and **0 of 81 production plans typed anything**. So a paywall showed
 * the store's real price with no cadence beside it.
 *
 * 🔴 ADDITIVE ON PURPOSE. An authored `period` wins, so no published paywall changes appearance —
 * an author who typed "mo" keeps "mo". Only a plan that authored nothing gains the store's value.
 * The alternative (store wins) would silently rewrite authored marketing copy, which is a bigger
 * behavioural change than this issue asked for.
 */
class StorePeriodDisplayTest {

    private fun plan(authored: String?, storeIso: String?) = PaywallPlan(
        id = "p",
        product_id = "app.premium.monthly",
        name = "Monthly",
        price = "",
        period = authored,
        resolvedStorePeriod = storeIso,
    )

    @Test
    fun `an authored period wins verbatim`() {
        // The behaviour that must NOT change for any existing paywall.
        assertEquals("mo", plan("mo", "P1M").displayPeriod)
        assertEquals("per month", plan("per month", "P1Y").displayPeriod)
    }

    @Test
    fun `an unauthored period reads the STORE, phrased`() {
        assertEquals("/month", plan(null, "P1M").displayPeriod)
        assertEquals("/year", plan(null, "P1Y").displayPeriod)
        assertEquals("/week", plan(null, "P1W").displayPeriod)
        assertEquals("/3 months", plan(null, "P3M").displayPeriod)
    }

    @Test
    fun `a blank authored period is treated as unauthored`() {
        // The console writes "" for a cleared field, and `""` must not beat a real store value —
        // `?: ` alone would keep the empty string and draw nothing.
        assertEquals("/month", plan("", "P1M").displayPeriod)
        assertEquals("/month", plan("   ", "P1M").displayPeriod)
    }

    @Test
    fun `nothing authored and nothing from the store draws nothing`() {
        // 🔴 Null, not a default. A plan with no cadence anywhere must render no period rather than
        // inventing "/month" — a wrong cadence beside a real price is worse than none.
        assertNull(plan(null, null).displayPeriod)
        assertNull(plan("", null).displayPeriod)
        // A one-time product has no billing period, which is correct rather than missing.
        assertNull(plan(null, "").displayPeriod)
    }

    @Test
    fun `a store period we cannot parse draws nothing rather than a guess`() {
        assertNull(plan(null, "MONTHLY").displayPeriod)
        assertNull(plan(null, "PT1H").displayPeriod)
    }

    // ── the substitution walks every place plans live ───────────────────────────────────────────

    @Test
    fun `applyingStorePeriods reaches the SECTION's plans, not only the root array`() {
        /*
         * 🔴 THE STORE-PRICE BUG, NOT REPEATED. The first version of the store-PRICE substitution mapped
         * only the root `plans` array, while every real paywall carries its plans in
         * `layout.sections[].config.plans` — so the feature passed its fixture and reached nothing.
         */
        val map = mapOf(
            "id" to "pw_1",
            "name" to "Periods",
            "plans" to listOf(
                mapOf("id" to "root_plan", "product_id" to "app.premium.annual", "name" to "Annual"),
            ),
            "sections" to listOf(
                mapOf(
                    "id" to "sec_plans",
                    "type" to "plans",
                    "data" to mapOf(
                        "plans" to listOf(
                            mapOf("id" to "sec_plan", "product_id" to "app.premium.monthly", "name" to "Monthly"),
                        ),
                    ),
                ),
            ),
        )
        val config = PaywallConfigParser.parseSinglePaywall("pw_1", map)
            ?: error("paywall did not parse")
        val applied = config.applyingStorePeriods(
            mapOf("app.premium.annual" to "P1Y", "app.premium.monthly" to "P1M"),
        )

        assertEquals("/year", applied.plans?.first()?.displayPeriod)
        val sectionPlan = applied.sections.first { it.type == "plans" }.data?.plans?.first()
        assertEquals("/month", sectionPlan?.displayPeriod)
    }

    @Test
    fun `an empty period map is a no-op`() {
        // The control: a store that answered nothing must leave the config untouched rather than
        // clearing what was already there.
        val map = mapOf(
            "id" to "pw_2",
            "name" to "Untouched",
            "plans" to listOf(
                mapOf("id" to "p", "product_id" to "x", "name" to "P", "period" to "mo"),
            ),
        )
        val config = PaywallConfigParser.parseSinglePaywall("pw_2", map)!!
        assertEquals(config, config.applyingStorePeriods(emptyMap()))
        assertEquals("mo", config.applyingStorePeriods(emptyMap()).plans?.first()?.displayPeriod)
    }

    // ── the STORE's free trial, and the promise rule ─────────────────────────────

    private fun trialPlan(authoredLabel: String?, authoredDuration: String?, storeTrial: String?) =
        PaywallPlan(
            id = "p", product_id = "app.premium.monthly", name = "Monthly", price = "",
            trial = authoredLabel?.let { PaywallPlanTrial(label = it) },
            trial_duration = authoredDuration,
            resolvedStoreTrial = storeTrial,
        )

    @Test
    fun `an authored trial label wins over the store`() {
        // The behaviour that must NOT change for any published paywall.
        assertEquals("Free week", trialPlan("Free week", null, "P1M").trialLabel)
        assertEquals("7-day free trial", trialPlan(null, "7-day", "P1M").trialLabel)
    }

    @Test
    fun `an unauthored trial reads the STORE, phrased`() {
        assertEquals("7 days free trial", trialPlan(null, null, "P7D").trialLabel)
        assertEquals("1 week free trial", trialPlan(null, null, "P1W").trialLabel)
        assertEquals("1 month free trial", trialPlan(null, null, "P1M").trialLabel)
    }

    @Test
    fun `no store trial means NO SENTENCE, never an invented one`() {
        /*
         * 🔴 THE PROMISE RULE. `resolvedStoreTrial` is null when Play returned no free-trial offer
         * for this user — which is Play saying they are not eligible, because it filters the offer
         * list. Rendering "7 days free" there would promise a trial the purchase sheet refuses.
         */
        assertNull(trialPlan(null, null, null).trialLabel)
    }

    @Test
    fun `a store trial we cannot parse draws nothing rather than a guess`() {
        assertNull(trialPlan(null, null, "WEEKLY").trialLabel)
        assertNull(trialPlan(null, null, "PT1H").trialLabel)
    }

    @Test
    fun `applyingStoreTrials reaches the SECTION's plans, not only the root array`() {
        // The store-price bug, not repeated: every real paywall keeps its plans in the section.
        val map = mapOf(
            "id" to "pw_t", "name" to "Trials",
            "plans" to listOf(mapOf("id" to "root", "product_id" to "app.premium.annual", "name" to "Annual")),
            "sections" to listOf(
                mapOf("id" to "s", "type" to "plans", "data" to mapOf(
                    "plans" to listOf(mapOf("id" to "sec", "product_id" to "app.premium.monthly", "name" to "Monthly")),
                )),
            ),
        )
        val config = PaywallConfigParser.parseSinglePaywall("pw_t", map)!!
        val applied = config.applyingStoreTrials(
            mapOf("app.premium.annual" to "P1M", "app.premium.monthly" to "P7D"),
        )
        assertEquals("1 month free trial", applied.plans?.first()?.trialLabel)
        assertEquals(
            "7 days free trial",
            applied.sections.first { it.type == "plans" }.data?.plans?.first()?.trialLabel,
        )
    }

    @Test
    fun `an empty trial map is a no-op`() {
        val map = mapOf("id" to "pw_n", "name" to "N",
            "plans" to listOf(mapOf("id" to "p", "product_id" to "x", "name" to "P", "trial_duration" to "7-day")))
        val config = PaywallConfigParser.parseSinglePaywall("pw_n", map)!!
        assertEquals(config, config.applyingStoreTrials(emptyMap()))
    }
}
