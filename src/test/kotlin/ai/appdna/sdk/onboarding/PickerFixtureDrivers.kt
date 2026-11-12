package ai.appdna.sdk.onboarding

/**
 * Android drivers for the picker fixture kinds, dispatched from `SharedFixtureTest`. Every value they
 * return is produced by REAL SDK code:
 *
 *   resolve_measurement_palette  REAL: `measurementPalette(cfg, highlight, dark)`, the rule
 *                                `MeasurementWheelBlock` draws every colour from.
 *   resolve_measurement_linear   REAL: `measurementLinearValue`, `measurementLinearFraction`, `measurementLinearSizes`.
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

    /** `action.cases[] = {op: value_at | fraction_of | sizes, ...}` → the linear slider's rules, per case. */
    @Suppress("UNCHECKED_CAST")
    fun linear(cases: List<Map<String, Any?>>): List<Any?> =
        cases.map { c ->
            val u = (c["unit"] as? Map<String, Any?>) ?: emptyMap()
            fun num(v: Any?) = (v as? Number)?.toDouble() ?: 0.0
            val unit = MeasurementUnit(id = "u", label = "u", min = num(u["min"]), max = num(u["max"]), step = num(u["step"]),
                decimals = num(u["decimals"]).toInt(), factor = 1.0, offset = 0.0)
            when (c["op"]) {
                "value_at" -> measurementLinearValue(num(c["fraction"]), unit)
                "fraction_of" -> measurementLinearFraction(num(c["value"]), unit)
                "sizes" -> measurementLinearSizes((c["field_config"] as? Map<String, Any?>) ?: emptyMap()).let {
                    linkedMapOf("height" to it.height, "track_width" to it.trackWidth, "thumb_size" to it.thumbSize)
                }
                else -> throw AssertionError("unknown op ${c["op"]}")
            }
        }

    /** REAL: `DatePickerStyle.standalone` / `formField`, which the Date Picker and the Date field draw from. */
    fun datePickerStyle(cases: List<Map<String, Any?>>): List<String> =
        cases.map { c ->
            if (c["surface"] == "form") DatePickerStyle.formField(c["presentation"] as? String, c["variant"] as? String, c["has_date"] as? Boolean ?: true)
            else DatePickerStyle.standalone(c["mode"] as? String, c["variant"] as? String, c["selection"] as? String)
        }

    /** REAL: `DateRangeSelection.tapping` and `answer`, which the range calendar calls on every tap. */
    @Suppress("UNCHECKED_CAST")
    fun dateRange(cases: List<Map<String, Any?>>): List<Map<String, Any?>> =
        cases.map { c ->
            var sel = DateRangeSelection()
            for (t in (c["taps"] as? List<String>) ?: emptyList()) sel = sel.tapping(t)
            sel.answer(c["field_id"] as? String ?: "f")
        }
}
