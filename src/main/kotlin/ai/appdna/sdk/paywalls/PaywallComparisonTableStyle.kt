package ai.appdna.sdk.paywalls

/**
 * What a `comparison_table` section draws with, resolved from its config and the page it sits on.
 *
 * The console offered header background and header / cell typography, but the renderer read none of the styling: it
 * drew the header row on a fixed `#F9FAFB` with the Material theme's text colour, so a dark paywall could draw its
 * headers as light text on near-white. It also read `check_color` while the console had no control for it.
 *
 * This is the one place those keys are read. The composable draws only what this returns, and the shared fixture
 * `paywall_comparison_table_style` pins it here, in iOS `PaywallComparisonTableStyle` and in the console preview.
 *
 * Defaults, when nothing is authored: the header row is the page's readable ink at 6% (`headerBgHex == null`);
 * header and cell text are that ink, and feature names that ink at 70%; a column's own `color` sets its header text
 * and its check marks, then `header_text_color` and `check_color`; the top-left header cell is EMPTY unless
 * `feature_header` names it; rows alternate only when `row_alt_bg_color` is set.
 */
internal object PaywallComparisonTableStyle {

    data class Column(
        val label: String,
        val highlighted: Boolean,
        val headerTextHex: String,
        val checkHex: String,
    )

    data class Resolved(
        val featureHeader: String,
        /** null = the page's readable ink at [DEFAULT_HEADER_ALPHA]. */
        val headerBgHex: String?,
        val headerTextHex: String,
        val headerFontSize: Double,
        val headerFontWeight: Int,
        val cellTextHex: String,
        /** The feature-name column's opacity: 0.7 unless `cell_text_color` is authored. */
        val featureTextAlpha: Double,
        val cellFontSize: Double,
        val cellFontWeight: Int,
        val rowAltBgHex: String?,
        val crossHex: String,
        val partialHex: String,
        val borderHex: String,
        /** null = the brand accent. */
        val highlightHex: String?,
        val cornerRadius: Double,
        val columns: List<Column>,
    )

    const val DEFAULT_HEADER_ALPHA = 0.06f
    const val DEFAULT_CHECK = "#22C55E"
    const val DEFAULT_CROSS = "#D1D5DB"
    const val DEFAULT_BORDER = "#E5E7EB"
    const val DEFAULT_PARTIAL = "#FBBF24"

    /** What a cell value draws as: `check`, `cross`, `partial`, or `text` (the value itself). */
    fun cellKind(value: String): String = when (value.trim().lowercase()) {
        "check", "true", "yes", "y", "✓" -> "check"
        "cross", "false", "no", "n", "-", "✗", "✕" -> "cross"
        "partial", "~" -> "partial"
        else -> "text"
    }

    /** The same CSS-number ladder as iOS `paywallFontWeightValue`. */
    fun weightValue(raw: String?, fallback: Int): Int = when (raw?.lowercase()) {
        "normal", "regular" -> 400
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        else -> fallback
    }

    fun resolve(cfg: PaywallSectionData?, pageBackground: String?): Resolved {
        val ink = PaywallTextDefaults.readableHex(pageBackground)
        val headerText = nonEmpty(cfg?.header_text_color) ?: ink
        val check = nonEmpty(cfg?.check_color) ?: DEFAULT_CHECK
        val cellText = nonEmpty(cfg?.cell_text_color)
        val columns = (cfg?.table_columns ?: emptyList()).map { col ->
            val own = nonEmpty(col.color)
            Column(
                label = col.label,
                highlighted = col.highlighted == true,
                headerTextHex = own ?: headerText,
                checkHex = own ?: check,
            )
        }
        return Resolved(
            featureHeader = cfg?.feature_header ?: "",
            headerBgHex = nonEmpty(cfg?.header_bg_color),
            headerTextHex = headerText,
            headerFontSize = (cfg?.header_font_size ?: 12f).toDouble(),
            headerFontWeight = weightValue(cfg?.header_font_weight, 700),
            cellTextHex = cellText ?: ink,
            featureTextAlpha = if (cellText == null) 0.7 else 1.0,
            cellFontSize = (cfg?.cell_font_size ?: 12f).toDouble(),
            cellFontWeight = weightValue(cfg?.cell_font_weight, 400),
            rowAltBgHex = nonEmpty(cfg?.row_alt_bg_color),
            crossHex = nonEmpty(cfg?.cross_color) ?: DEFAULT_CROSS,
            partialHex = nonEmpty(cfg?.partial_color) ?: DEFAULT_PARTIAL,
            borderHex = nonEmpty(cfg?.border_color) ?: DEFAULT_BORDER,
            highlightHex = nonEmpty(cfg?.highlight_color),
            cornerRadius = (cfg?.corner_radius ?: cfg?.border_radius ?: 12f).toDouble(),
            columns = columns,
        )
    }

    private fun nonEmpty(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }
}
