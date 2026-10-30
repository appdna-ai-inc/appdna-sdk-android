package ai.appdna.sdk.paywalls

/**
 * The order a paywall's plans draw in.
 *
 * 🔴 `PaywallPlan.sort_order` WAS DECODED BY BOTH PLATFORMS AND READ BY NEITHER. Plan order came
 * from the array's own order, so a document that carried an explicit rank — a generator's output,
 * an import, a hand-written paywall — drew its plans in whatever order they happened to be
 * serialised in, with no sign that an authored rank had been dropped.
 *
 * 🔴 THE RANK IS `sort_order ?: index`, AND THE SORT IS STABLE. Sorting only when EVERY plan
 * carries a value would make the field useless the moment one plan lacked it; treating a missing
 * value as 0 would silently promote it to the front. Using the plan's own array position as its
 * rank means a plan with no `sort_order` stays exactly where it is, a plan with one moves to that
 * rank, and two plans claiming the same rank keep their relative order — so nothing published today
 * can reorder, which is the risk an explicit rank beside an implicit one carries.
 *
 * Mirrors iOS `PaywallPlanOrder`.
 */
internal object PaywallPlanOrder {

    /** [plans] in the order they should be drawn. */
    fun sorted(plans: List<PaywallPlan>): List<PaywallPlan> {
        // Nothing to decide, and no allocation for the common case.
        if (plans.none { it.sort_order != null }) return plans
        // `sortedBy` is STABLE on the JVM, so plans at the same rank keep their relative order
        // without a tiebreaker — unlike Swift's `sorted(by:)`, which needs one made explicit.
        return plans.withIndex()
            .sortedBy { (index, plan) -> plan.sort_order ?: index }
            .map { it.value }
    }
}
