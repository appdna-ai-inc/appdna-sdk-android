package ai.appdna.sdk.onboarding

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Time and date-and-time answers: the shapes they are stored in, the older shapes still read, and the text a template
 * draws for them. All in one zone: the app's ([ai.appdna.sdk.AppDNA.appTimeZoneId]), UTC when the app has none.
 *
 * - date-and-time: `2026-11-10T15:03:00+01:00` (UTC as `Z`);
 * - time: `15:03:00+01:00`, the offset the zone had on the day it was picked. A time of day has no date, so a full
 *   moment would put a meaningless date into every template that shows it.
 *
 * Older answers still read: a UTC instant (`2026-11-10T14:03:00Z`), and the phone-local `HH:mm` and
 * `yyyy-MM-dd HH:mm`, which are read in the zone given. iOS `AnswerTime`.
 */
internal object AnswerTime {
    val zone: TimeZone
        get() = ai.appdna.sdk.AppDNA.appTimeZoneId?.let(::zoneOf) ?: TimeZone.getTimeZone("UTC")

    /** The locale a drawn date answer is formatted in: the device's. Settable so a test can pin it. iOS `displayLocale`. */
    @Volatile var displayLocale: Locale? = null

    /** The zone an IANA id names, or null for an id this runtime does not know (never silently GMT). */
    fun zoneOf(id: String): TimeZone? = TimeZone.getTimeZone(id).takeIf { it.id == id }

    private fun offsetText(zone: TimeZone, atMs: Long): String {
        val total = zone.getOffset(atMs) / 60000
        if (total == 0) return "Z"
        val a = kotlin.math.abs(total)
        return String.format(Locale.US, "%s%02d:%02d", if (total < 0) "-" else "+", a / 60, a % 60)
    }

    private fun cal(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Calendar =
        GregorianCalendar(zone, Locale.US).apply { clear(); set(y, mo - 1, d, h, mi, s) }

    /** The wall time [y]-[mo]-[d] [h]:[mi] in [zone], as stored. */
    fun dateTimeString(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: TimeZone): String =
        String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:00", y, mo, d, h, mi) + offsetText(zone, cal(zone, y, mo, d, h, mi).timeInMillis)

    /** The wall time [h]:[mi] in [zone] on the day [y]-[mo]-[d], as stored. */
    fun timeString(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: TimeZone): String =
        String.format(Locale.US, "%02d:%02d:00", h, mi) + offsetText(zone, cal(zone, y, mo, d, h, mi).timeInMillis)

    private const val OFFSET = """(Z|[+-]\d{2}:?\d{2})"""
    private val dateTimeRe = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$OFFSET$""")
    private val timeRe = Regex("""^(\d{2}):(\d{2})(?::(\d{2}))?$OFFSET$""")
    private val localDateTimeRe = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})$""")
    private val localTimeRe = Regex("""^(\d{2}):(\d{2})$""")
    private val dateRe = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /** Milliseconds east of UTC for `Z`, `+01:00` or `+0100`. */
    private fun offsetMs(s: String): Int? {
        if (s == "Z") return 0
        val digits = s.drop(1).replace(":", "")
        if (digits.length != 4) return null
        val h = digits.take(2).toIntOrNull() ?: return null
        val m = digits.takeLast(2).toIntOrNull() ?: return null
        if (h >= 24 || m >= 60) return null
        return (if (s.startsWith("-")) -1 else 1) * (h * 3600 + m * 60) * 1000
    }

    private fun fixed(offset: Int): TimeZone = java.util.SimpleTimeZone(offset, "fixed")

    private fun moment(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, zone: TimeZone): Long? {
        if (mo !in 1..12 || d !in 1..31 || h >= 24 || mi >= 60 || s >= 60) return null
        val c = cal(zone, y, mo, d, h, mi, s)
        return if (c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) + 1 == mo && c.get(Calendar.DAY_OF_MONTH) == d) c.timeInMillis else null
    }

    private fun onDay(nowMs: Long, zone: TimeZone, h: Int, mi: Int, s: Int): Long? {
        val day = GregorianCalendar(zone, Locale.US).apply { timeInMillis = nowMs }
        return moment(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH), h, mi, s, zone)
    }

    /**
     * The moment, in epoch milliseconds, a stored time or date-and-time answer names. A time alone is placed on the day
     * [nowMs] falls on in its own offset (or, for an older phone-local time, in [zone]). Null for anything else.
     */
    fun epochMs(text: String, zone: TimeZone, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = text.trim()
        fun g(m: MatchResult, i: Int) = m.groupValues[i].toIntOrNull() ?: 0
        dateTimeRe.matchEntire(s)?.let { m ->
            val off = offsetMs(m.groupValues[7]) ?: return null
            return moment(g(m, 1), g(m, 2), g(m, 3), g(m, 4), g(m, 5), g(m, 6), fixed(off))
        }
        timeRe.matchEntire(s)?.let { m ->
            val off = offsetMs(m.groupValues[4]) ?: return null
            return onDay(nowMs, fixed(off), g(m, 1), g(m, 2), g(m, 3))
        }
        localDateTimeRe.matchEntire(s)?.let { m -> return moment(g(m, 1), g(m, 2), g(m, 3), g(m, 4), g(m, 5), 0, zone) }
        localTimeRe.matchEntire(s)?.let { m -> return onDay(nowMs, zone, g(m, 1), g(m, 2), 0) }
        return null
    }

    /**
     * The text a template draws for a stored answer: a date-and-time as `yyyy-MM-dd HH:mm` and a time as `HH:mm`, in
     * [zone]; a date (`yyyy-MM-dd`, a calendar day with no zone) in [locale]'s medium style on the Gregorian calendar
     * ("Mar 15, 1990", "15.03.1990"). Null for anything else (a phone-local older answer, any other text), which is
     * drawn as stored. The stored and sent value stays ISO. iOS `AnswerTime.display`.
     */
    fun display(text: String, zone: TimeZone, nowMs: Long = System.currentTimeMillis(), locale: Locale = displayLocale ?: Locale.getDefault()): String? {
        val s = text.trim()
        dateRe.matchEntire(s)?.let { m ->
            return dateText(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), locale)
        }
        val isDateTime = dateTimeRe.matches(s)
        if (!isDateTime && !timeRe.matches(s)) return null
        val ms = epochMs(s, zone, nowMs) ?: return null
        val c = GregorianCalendar(zone, Locale.US).apply { timeInMillis = ms }
        val hm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (isDateTime) String.format(Locale.US, "%04d-%02d-%02d ", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) + hm else hm
    }

    /**
     * A calendar day in [locale]'s medium date style, or null for a day that does not exist (`2026-02-30`). The day is
     * placed and formatted in UTC, so no zone can move it. iOS `AnswerTime.dateText`.
     */
    fun dateText(y: Int, mo: Int, d: Int, locale: Locale): String? {
        val utc = TimeZone.getTimeZone("UTC")
        val ms = moment(y, mo, d, 12, 0, 0, utc) ?: return null
        val f = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
        f.calendar = GregorianCalendar(utc, locale)
        f.timeZone = utc
        return f.format(java.util.Date(ms))
    }
}
