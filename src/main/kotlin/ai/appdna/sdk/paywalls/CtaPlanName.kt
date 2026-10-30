package ai.appdna.sdk.paywalls

/**
 * How a multi-plan CTA names the plan it will buy.
 *
 * One rule on every surface: iOS `CTAPlanName`, this, and the console preview's `composeCtaPlanName`;
 * the shared fixture `dto_parsing/cta_plan_name_compose` holds all three to the same output.
 *
 *  - `base` is the CTA text ALREADY localised; `plan` the plan's name, already localised, or null when
 *    there is nothing to name (a single plan, a plan with its own `cta_text`, or naming switched off).
 *  - `format` places the two: `{cta}` and `{plan}`, default `{cta} — {plan}`. It is itself localised
 *    (`cta.plan_format`), so a locale can change the separator OR the order.
 *  - A label that already contains the plan's name is left alone, so "Start Annual" never becomes
 *    "Start Annual — Annual", whichever string localisation produced.
 */
internal object CtaPlanName {
    const val DEFAULT_FORMAT = "{cta} — {plan}"

    fun compose(base: String, plan: String?, format: String?): String {
        val name = plan?.trim().orEmpty()
        if (name.isEmpty() || base.contains(name, ignoreCase = true)) return base
        val f = if (format.isNullOrEmpty()) DEFAULT_FORMAT else format
        return f.replace("{cta}", base).replace("{plan}", name)
    }
}
