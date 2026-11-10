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

    /** Whether [mode] has a time column: its wheel works in the app's zone ([AnswerTime.zone]). */
    fun hasTime(mode: String) = mode == "datetime" || mode == "date_time" || mode == "time"

    /**
     * What the wheel stores for a pick: four digits for a year, `yyyy-MM-dd` for a date, and for a time or a
     * date-and-time the wall time the wheel shows in [zone] with that zone's offset ([AnswerTime]), as iOS stores them.
     */
    fun answer(mode: String, date: Ymd, hour: Int, minute: Int, zone: java.util.TimeZone = AnswerTime.zone): String = when {
        mode == "year" -> String.format(Locale.US, "%04d", date.year)
        mode == "time" -> AnswerTime.timeString(date.year, date.month, date.day, hour, minute, zone)
        hasTime(mode) -> AnswerTime.dateTimeString(date.year, date.month, date.day, hour, minute, zone)
        else -> String.format(Locale.US, "%04d-%02d-%02d", date.year, date.month, date.day)
    }

    /**
     * A stored answer read back as the date the wheel shows, in [zone]: what [readSaved] reads; a year-only answer, as
     * the middle of that year (as iOS reads it); or an ISO instant, which iOS stored for a date before it stored the
     * calendar date, read as the local date it was picked on. Null for anything else.
     */
    private val offsetSuffix = Regex("""(Z|[+-]\d{2}:?\d{2})$""")
    private val localTime = Regex("""^(\d{2}):(\d{2})$""")

    fun readAnswer(saved: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Calendar? {
        val t = saved?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (Regex("""^\d{4}$""").matches(t)) {
            return Calendar.getInstance(zone).apply { clear(); set(t.toInt(), Calendar.JUNE, 15, 12, 0) }
        }
        // A time or date-and-time carrying an offset, an older UTC instant included: the moment it names, seen in [zone].
        // The phone-local older shapes (`HH:mm`, `yyyy-MM-dd HH:mm`) are read below, as wall time in [zone].
        if (t.contains('T') || offsetSuffix.containsMatchIn(t)) {
            val ms = AnswerTime.epochMs(t, zone) ?: ai.appdna.sdk.core.IsoInstant.parseEpochMs(t) ?: return null
            return Calendar.getInstance(zone).apply { timeInMillis = ms }
        }
        localTime.matchEntire(t)?.let { m ->
            return Calendar.getInstance(zone).apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt()); set(Calendar.SECOND, 0) }
        }
        return readSaved(t, zone)
    }

    /**
     * A date as the wheel writes it, read in [zone]: `yyyy-MM-dd`, optionally followed by ` HH:mm`. Null for anything
     * else, so a garbled value falls back to the authored default.
     */
    fun readSaved(saved: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Calendar? {
        val t = saved?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val datePart = t.substringBefore(' ')
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(datePart) ?: return null
        val (y, mo, d) = m.destructured
        // Reject an out-of-range month or day: a lenient Calendar would roll "2000-13-45" over to a real but wrong date.
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
        val cal = Calendar.getInstance(zone)
        cal.isLenient = false
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt())
        try { cal.time } catch (e: Exception) { return null }  // Feb 30
        cal.isLenient = true
        // A bare date is that day's midnight, as iOS reads it: a date-and-time wheel seeded from one opens at 00:00, not
        // at whatever time it happens to be.
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        Regex("""(\d{2}):(\d{2})""").find(t.substringAfter(' ', ""))?.let { tm ->
            val (h, mi) = tm.destructured
            cal.set(Calendar.HOUR_OF_DAY, h.toInt())
            cal.set(Calendar.MINUTE, mi.toInt())
        }
        return cal
    }

    /** The nearest real, allowed date: the day comes down for a shorter month, then the bounds apply. */
    fun clamp(date: Ymd, minimum: Ymd?, maximum: Ymd?): Ymd {
        var result = date.copy(day = date.day.coerceIn(1, daysInMonth(date.year, date.month)))
        if (minimum != null && result < minimum) result = minimum
        if (maximum != null && result > maximum) result = maximum
        return result
    }
}
