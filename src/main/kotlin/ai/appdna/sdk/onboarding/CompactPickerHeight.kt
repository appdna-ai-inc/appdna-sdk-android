package ai.appdna.sdk.onboarding

/*
 * #1052 — HOW TALL A COMPACT DATE PILL IS, as one rule both platforms call.
 *
 * iOS has read `field_style.height` as a `sm | md | lg` shorthand since the compact picker
 * shipped, alongside a numeric `field_config.input_height` that takes precedence. Android read
 * NEITHER, and no console control wrote either — so a real iOS capability was reachable by nobody
 * and ignored by this platform. Three surfaces, three answers.
 *
 * The rule lives here rather than inside the composable for the reason #998's `LikertScaleRule`
 * does: a clamp written twice in two languages agrees until one of them is edited (#1037), and the
 * shared behavioural fixture has to call the same function the renderer calls or it only agrees
 * with itself.
 *
 * 🔴 The wheel and graphical variants set their own height and ignore all of this. Only the
 * compact pill asks.
 */
object CompactPickerHeight {
    /** What the pill measures when nothing is authored. */
    const val UNSET = 44.0

    /** `sm` · `md` · `lg`, matched case-insensitively. Anything else is unauthored. */
    @JvmStatic
    fun semantic(token: String?): Double? = when (token?.lowercase()) {
        "sm" -> 36.0
        "md" -> 44.0
        "lg" -> 56.0
        else -> null
    }

    /**
     * The height to draw.
     *
     * A NUMBER wins over the token, because `field_config.input_height` is the more specific of
     * the two and that is the precedence iOS already shipped. A number that is absurd is ignored
     * rather than drawn: a pill cannot usefully be 2 points or 2000, and a config that says so is
     * a mistake, not an instruction.
     */
    @JvmStatic
    fun resolve(numeric: Double?, token: String?): Double {
        if (numeric != null && numeric >= 20.0 && numeric <= 200.0) return numeric
        return semantic(token) ?: UNSET
    }
}
