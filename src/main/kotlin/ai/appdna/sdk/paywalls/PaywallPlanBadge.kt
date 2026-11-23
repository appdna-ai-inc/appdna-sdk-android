package ai.appdna.sdk.paywalls

/**
 * Whether, and where, a plan layout draws a plan's badge ("Best value", "Most popular").
 *
 * Three plan layouts used to disagree across the surfaces. `pill_selector` drew the badge on Android
 * only, `segmented_toggle` in the console preview only, and `product_as_cta` nowhere — so an author saw
 * a badge in the preview that no phone drew, or an Android user saw one an iPhone user did not. They now
 * all draw it. Every renderer asks this one question, and the
 * shared fixtures `paywall_plan_badge_*` pin the answers here and in iOS `PaywallPlanBadge`; the console
 * preview answers it with `resolvePlanBadgePlacement`.
 *
 * Placements:
 * - `below_price` — inside the pill, under the price (`pill_selector`).
 * - `above_segment` — centred over its own segment, in a row above the control (`segmented_toggle`).
 *   iOS's `Picker(.segmented)` cannot hold a view inside a segment, so the row is how a badge belongs to one.
 * - `top_left` / `top_center` / `top_right` — straddling the button's top edge, as on a card
 *   (`product_as_cta`, which honours the authored `badge_position`).
 * - `inline` — in the button's label, under the plan name (`product_as_cta` with `badge_position: inline`).
 * - `card` — any other layout: the plan card decides, as it always has.
 */
internal object PaywallPlanBadge {

    /** The text a plan's badge draws, or null when there is nothing to draw. A blank badge draws nothing. */
    fun text(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    fun placement(layout: String?, badgePosition: String?): String = when (layout) {
        "pill_selector" -> "below_price"
        "segmented_toggle", "segmented" -> "above_segment"
        "product_as_cta" -> when (val p = (badgePosition ?: "top_right").replace("-", "_").lowercase()) {
            "top_left", "top_center", "inline" -> p
            else -> "top_right"
        }
        else -> "card"
    }

    /** Test tag on every badge these layouts draw, so a render test can count them. */
    const val TEST_TAG = "paywall.plan.badge"
}
