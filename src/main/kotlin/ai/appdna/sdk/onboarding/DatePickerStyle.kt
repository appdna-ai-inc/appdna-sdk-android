package ai.appdna.sdk.onboarding

/**
 * Which picker a date block draws, decided in one place and pinned by the `resolve_date_picker_style` fixture
 * (iOS `DatePickerStyle.swift`, the console preview's `date-picker-style.ts`).
 *
 * - A **Date Picker** (`date_wheel_picker`) draws `field_config.picker_variant` — `graphical` (a month calendar),
 *   `wheel` or `compact` — and a date-only picker with `field_config.selection_mode: "range"` draws the range
 *   calendar. Unset: a wheel for a date-and-time or a year, otherwise the calendar. This platform used to draw the
 *   wheel for every Date Picker, whatever its "Picker Style" said.
 * - A **Date** form field (`input_date` / `input_datetime`) used to draw the calendar whenever its presentation was
 *   `inline` — the console's default — so its "Picker Variant" changed nothing. The variant now decides for an inline
 *   field too; `field` presentation is the compact tap-to-open field, as before; with neither set, compact.
 */
object DatePickerStyle {
    private val variants = setOf("graphical", "wheel", "compact")

    private fun clean(s: String?): String? = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** `date_wheel_picker`: `graphical` | `wheel` | `compact` | `range`. `mode` is `picker_mode`. */
    fun standalone(mode: String?, variant: String?, selection: String?): String {
        val m = clean(mode) ?: "date"
        if (m == "year" || m == "time") return "wheel"
        val hasTime = m == "datetime" || m == "date_time"
        if (!hasTime && clean(selection) == "range") return "range"
        clean(variant)?.takeIf { it in variants }?.let { return it }
        return if (hasTime) "wheel" else "graphical"
    }

    /** `input_date` / `input_time` / `input_datetime`: `graphical` | `wheel` | `compact`. */
    fun formField(presentation: String?, variant: String?, hasDate: Boolean): String {
        val p = clean(presentation)
        if (p == "field") return "compact"
        val v = clean(variant)?.takeIf { it in variants }
        if (p == "inline") return v ?: if (hasDate) "graphical" else "compact"
        return v ?: "compact"
    }
}

/**
 * A range of days picked on the range calendar. Days are `yyyy-MM-dd` strings, which order as dates.
 *
 * A first tap starts the range; a second tap on or after the start ends it; a tap before the start, or any tap once
 * the range is complete, starts a new one. Pinned by the `resolve_date_range` fixture.
 */
data class DateRangeSelection(val start: String? = null, val end: String? = null) {
    fun tapping(day: String): DateRangeSelection {
        val s = start
        return if (s == null || end != null || day < s) DateRangeSelection(day, null) else DateRangeSelection(s, day)
    }

    /**
     * The answer keys: `<field>` = `start/end` once both ends are picked, with `_start`, `_end` and `_days`
     * (inclusive). A started range writes `_start` only. A `null` value is a key to remove.
     */
    fun answer(fieldId: String): Map<String, Any?> {
        val s = start ?: return linkedMapOf(fieldId to null, "${fieldId}_start" to null, "${fieldId}_end" to null, "${fieldId}_days" to null)
        val e = end ?: return linkedMapOf(fieldId to null, "${fieldId}_start" to s, "${fieldId}_end" to null, "${fieldId}_days" to null)
        return linkedMapOf(fieldId to "$s/$e", "${fieldId}_start" to s, "${fieldId}_end" to e, "${fieldId}_days" to inclusiveDays(s, e))
    }

    companion object {
        /** UTC-midnight millis of a `yyyy-MM-dd` day (no `java.time`: minSdk is 24). */
        fun dayMillis(day: String): Long? {
            val p = day.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size != 3) return null
            return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(p[0], p[1] - 1, p[2])
            }.timeInMillis
        }

        fun inclusiveDays(a: String, b: String): Int {
            val ma = dayMillis(a) ?: return 0
            val mb = dayMillis(b) ?: return 0
            return Math.round((mb - ma) / 86_400_000.0).toInt() + 1
        }

        fun restore(values: Map<String, Any?>, fieldId: String): DateRangeSelection {
            (values[fieldId] as? String)?.split("/")?.takeIf { it.size == 2 }?.let { return DateRangeSelection(it[0], it[1]) }
            return DateRangeSelection(values["${fieldId}_start"] as? String, null)
        }
    }
}
