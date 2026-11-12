package ai.appdna.sdk.onboarding

/**
 * Android drivers for the picker fixture kinds, dispatched from `SharedFixtureTest`. Every value they
 * return is produced by REAL SDK code:
 *
 *   resolve_measurement_palette  REAL: `measurementPalette(cfg, highlight, dark)`, the rule
 *                                `MeasurementWheelBlock` draws every colour from.
 *
 * The runner converts the fixture's JSON to plain maps and lists (JSON null → null) before calling.
 */
internal object PickerFixtureDrivers {

    /** `action.cases[] = {name, field_config, highlight_color, dark}` → the palette's roles, per case. */
    @Suppress("UNCHECKED_CAST")
    fun palettes(cases: List<Map<String, Any?>>): List<Map<String, Any?>> =
        cases.map { c ->
            val cfg = (c["field_config"] as? Map<String, Any?>) ?: emptyMap()
            measurementPalette(cfg, c["highlight_color"] as? String, c["dark"] as? Boolean ?: false).roles()
        }
}
