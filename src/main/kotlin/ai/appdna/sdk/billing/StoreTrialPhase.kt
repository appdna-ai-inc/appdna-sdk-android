package ai.appdna.sdk.billing

/**
 * Which phase of a Play subscription's offers is a FREE TRIAL — #1062.
 *
 * 🔴 EXTRACTED SO IT CAN BE EXERCISED. This decision lived inline inside
 * [PriceResolver.getSubscriptionTrials], behind a live `BillingClient`, so the only thing any test
 * could reach was the query — and #1053's lesson is that a rule nothing can call is a rule nobody
 * has watched work. The iOS side of the same promise is proven against a real StoreKit with a
 * manufactured ineligible user (#933's `StoreTrialEligibilityHostedTests`); Play has no
 * `SKTestSession` equivalent, so this is the half that CAN be proven here, and it is the half we
 * own: Play decides which offers this user may buy, and we decide which of them is a free trial.
 *
 * 🔴 NOT MIRRORED ON iOS, deliberately. StoreKit hands over ONE `introductoryOffer` with a
 * `paymentMode`, so there the decision is `paymentMode == .freeTrial`; Play hands over N offers of
 * M phases each with a price. A shared fixture over those two shapes would be forcing a symmetry
 * the stores do not have — the shared assertion is `check:store-trial-promise`, which pins that
 * BOTH platforms gate the sentence on a positive signal and keep the store rung last.
 */
internal object StoreTrialPhase {

    /** One pricing phase, reduced to what the decision reads. */
    internal data class Phase(val priceAmountMicros: Long, val billingPeriod: String?)

    /**
     * The billing period of the first free-trial phase across [offers], or null when there is none.
     *
     * `offers` is every offer Play returned FOR THIS USER, each as its phases in order — Play has
     * already filtered to what this user may buy, which is why a zero-price phase among them is an
     * eligibility answer rather than a product fact.
     *
     * Across ALL offers, not just the first: a product can carry several, and a trial on any of
     * them is a trial this user can take.
     *
     * 🔴 ZERO micros, not "cheap". An introductory PRICE is non-zero and is a different promise —
     * "£1 for the first month" rendered as "1 month free" is the paywall lying about money, so a
     * non-zero phase is deliberately not a trial here.
     *
     * A blank or absent period is no trial either: there is nothing to phrase, and a free trial
     * sentence with no duration reads as a bug to the user.
     */
    fun firstFreeTrialPeriod(offers: List<List<Phase>>): String? =
        offers.asSequence()
            .flatMap { it.asSequence() }
            .firstOrNull { it.priceAmountMicros == 0L && !it.billingPeriod.isNullOrBlank() }
            ?.billingPeriod
}
