package ai.appdna.sdk.onboarding

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/** The date logic of the themed wheel: what exists and may be picked, the same rules iOS answers. */
internal object DateWheelModel {
    data class Ymd(val year: Int, val month: Int, val day: Int) : Comparable<Ymd> {
        override fun compareTo(other: Ymd) = compareValuesBy(this, other, Ymd::year, Ymd::month, Ymd::day)
    }

    /** The day, month and year columns in the order this locale writes a date. */
    fun dateColumns(locale: Locale): List<String> {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
        val seen = mutableListOf<String>()
        for (ch in pattern) {
            val column = when (ch) {
                'y', 'Y', 'u' -> "year"
                'M', 'L' -> "month"
                'd' -> "day"
                else -> null
            }
            if (column != null && column !in seen) seen.add(column)
        }
        return if (seen.size == 3) seen else listOf("month", "day", "year")
    }

    fun daysInMonth(year: Int, month: Int): Int =
        GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)

    /** A parsed bound, or today when the author refused the other side of it. */
    fun bound(parsed: Calendar?, untilToday: Boolean): Ymd? {
        val c = parsed ?: if (untilToday) Calendar.getInstance() else return null
        return Ymd(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** The nearest real, allowed date: the day comes down for a shorter month, then the bounds apply. */
    fun clamp(date: Ymd, minimum: Ymd?, maximum: Ymd?): Ymd {
        var result = date.copy(day = date.day.coerceIn(1, daysInMonth(date.year, date.month)))
        if (minimum != null && result < minimum) result = minimum
        if (maximum != null && result > maximum) result = maximum
        return result
    }
}
