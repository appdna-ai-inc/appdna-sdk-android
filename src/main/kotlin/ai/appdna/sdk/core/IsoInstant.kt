package ai.appdna.sdk.core

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * The date readers iOS uses, reproduced exactly, on every API level.
 *
 *  - [parseEpochMs] = iOS `ISO8601.date(from:)`: an `ISO8601DateFormatter` with
 *    `.withInternetDateTime` (+ `.withFractionalSeconds`). `2026-05-07T10:00:00Z`,
 *    `2026-05-07T10:00:00.000Z`, `2026-05-07T12:00:00+02:00`.
 *  - [parseDateOnlyUtcMs] = iOS `MessageManager.parseWindowDate`'s fallback: a `DateFormatter`
 *    `yyyy-MM-dd`, `en_US_POSIX`, UTC, non-lenient.
 *  - [parseDateOnlyLocalMs] = the same formatter with no time zone set (iOS `FormStepView.dateRange`, a
 *    form step's `min_date` / `max_date`): midnight in the device's time zone.
 *  - [parseZonelessUtcMs] = iOS `CountdownTimerBlockView.initialRemainingSeconds` (a countdown's
 *    `target_datetime`): a `DateFormatter` `yyyy-MM-dd'T'HH:mm:ss`, `en_US_POSIX`, UTC, non-lenient.
 *
 * Both iOS formatters are ICU parsers, and ICU is far more forgiving than `java.time` or
 * `SimpleDateFormat`. Android used `OffsetDateTime` on API 26+ and `SimpleDateFormat` on API 24/25,
 * so the two Android paths disagreed with each other AND with iOS (`2026-5-7T10:00:00Z` parsed only on
 * API 24/25; a surrounding space was trimmed on Android, not on iOS; `+02` and a lowercase `z` failed
 * on API 26+ …). This is one hand-written reader, the same on every API level, of what the iOS
 * formatters were MEASURED to accept (iOS 26 simulator; macOS Foundation gives identical answers):
 *
 *  - Leading whitespace is skipped (any Unicode whitespace or space separator — NBSP, U+3000, tab,
 *    newline; not U+200B / U+FEFF).
 *  - A digit is any Unicode decimal digit (`٢٠٢٦`, `２０２６`); a field has any number of digits
 *    (`2026-5-7`, `T1:00:00`, `2026-005-007`), and whitespace may precede a field.
 *  - Date separators: `-`, `.` or `/`, each optionally preceded by whitespace (or whitespace alone).
 *    Time separators: `:` or `.`, optionally preceded by whitespace.
 *  - A year of 12000–12099 is read as a two-digit year, 00–49 → 20xx, 50–99 → 19xx (`12026` → 2026). An
 *    ICU oddity, reproduced because iOS does it.
 *  - Dates are on the Julian/Gregorian calendar with the 1582 cut-over (ICU's and
 *    `java.util.GregorianCalendar`'s), so year 26 is where iOS puts it.
 *
 * Instant only: `T` (upper case only), then hour 0–24 (24 rolls into the next day), minute 0–59, second
 * 0–59 — seconds are REQUIRED (`T10:00Z` is null). An optional fraction `.ddd…` (whitespace may follow
 * the dot) truncated to milliseconds. Then optional whitespace and the zone: `Z`, `z`, or an ICU
 * ISO-8601 offset ([offsetMs]). Anything after the zone is ignored (ICU parses a prefix). The day may
 * be 1–31 and rolls over (`2026-02-30` → 2 March); the year may carry a `-` sign.
 *
 * Date-only: the year is 1–144683 and the date must exist (`2026-02-30` is null). After the day only
 * spaces (any Unicode space separator) and tabs may follow — a trailing newline makes it null, a leading
 * one does not.
 *
 * Zone-less (the countdown): the date part as date-only, then `T` straight after the day (upper case, no
 * whitespace before it), then hour, minute and second, each preceded by optional whitespace and separated
 * by `:`, `.` or `-` (optionally preceded by whitespace) or by whitespace alone; `/`, `,` and `_` are not
 * separators. Seconds are required. The date
 * must exist, hour 0–23, minute and second 0–59, year 1–144683. After the second only spaces and tabs may
 * follow — a zone (`Z`, `+02:00`), a fraction or a newline makes it null, as on iOS.
 *
 * Pinned on iOS AND on Android at API 24 and 33 by the shared fixtures
 * `dto_parsing/message_window_date_parsing`, `dto_parsing/countdown_target_datetime` and
 * `dto_parsing/form_date_bounds_parsing`, and by `IsoInstantTest`.
 */
internal object IsoInstant {

    fun parseEpochMs(iso: String): Long? = parseInstant(iso, fractional = true) ?: parseInstant(iso, fractional = false)

    fun parseDateOnlyUtcMs(value: String): Long? {
        val (year, month, day) = dateOnlyFields(value) ?: return null
        return epochMs(year, month, day, 0, 0, 0, 0)
    }

    /**
     * [parseDateOnlyUtcMs]'s rule, but midnight in [zone] (default: the device's time zone). The strings
     * accepted and the day are iOS's; for a date before the zone had standard time (before 1883 in Los
     * Angeles) the instant can be under an hour from iOS's, whose tz data applies the zone's local mean
     * time where the JVM's does not (pinned in `IsoInstantTest`'s `dateonly_lmt` rows).
     */
    fun parseDateOnlyLocalMs(value: String, zone: TimeZone = TimeZone.getDefault()): Long? {
        val (year, month, day) = dateOnlyFields(value) ?: return null
        return GregorianCalendar(zone, Locale.US).apply {
            clear()
            set(year.toInt(), month.toInt() - 1, day.toInt())
        }.timeInMillis
    }

    /** A zone-less `yyyy-MM-dd'T'HH:mm:ss` read in UTC, as iOS's non-lenient `DateFormatter` reads it. */
    fun parseZonelessUtcMs(value: String): Long? {
        val c = Cursor(value)
        c.skipWhitespace()
        val (year, month, day) = c.date(signedYear = false) ?: return null
        if (c.atEnd || c.peek != 'T') return null
        c.pos++
        val hour = c.number() ?: return null
        if (!c.zonelessTimeSeparator()) return null
        val minute = c.number() ?: return null
        if (!c.zonelessTimeSeparator()) return null
        val second = c.number() ?: return null
        if (!c.onlySpacesOrTabsLeft()) return null
        if (!isRealDate(year, month, day) || hour > 23 || minute > 59 || second > 59) return null
        return epochMs(year, month, day, hour, minute, second, 0)
    }

    /** The date-only rule's fields, or null (see the class comment). */
    private fun dateOnlyFields(value: String): Triple<Long, Long, Long>? {
        val c = Cursor(value)
        c.skipWhitespace()
        val ymd = c.date(signedYear = false) ?: return null
        if (!c.onlySpacesOrTabsLeft()) return null
        val (year, month, day) = ymd
        return if (isRealDate(year, month, day)) ymd else null
    }

    private fun isRealDate(year: Long, month: Long, day: Long): Boolean {
        if (year < 1 || year > 144_683 || month < 1 || month > 12 || day < 1) return false
        val cal = utcCalendar().apply { set(year.toInt(), month.toInt() - 1, 1) }
        return day <= cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }


    /** One `ISO8601DateFormatter` (with or without `.withFractionalSeconds`). */
    private fun parseInstant(s: String, fractional: Boolean): Long? {
        val c = Cursor(s)
        c.skipWhitespace()
        val (year, month, day) = c.date(signedYear = true) ?: return null
        if (c.atEnd || c.peek != 'T') return null
        c.pos++
        val hour = c.number() ?: return null
        if (!c.timeSeparator()) return null
        val minute = c.number() ?: return null
        if (!c.timeSeparator()) return null
        val second = c.number() ?: return null
        var millis = 0L
        if (fractional) {
            if (c.atEnd || c.peek != '.') return null
            c.pos++
            c.skipWhitespace()
            val start = c.pos
            val digits = StringBuilder()
            while (!c.atEnd && Character.digit(c.peek, 10) >= 0) {
                if (digits.length < 3) digits.append(Character.digit(c.peek, 10))
                c.pos++
            }
            if (c.pos == start) return null
            millis = digits.toString().padEnd(3, '0').toLong()
        }
        c.skipWhitespace()
        if (c.atEnd) return null
        val offset = when (val z = c.peek) {
            'Z', 'z' -> 0L
            '+', '-' -> (offsetMs(s, c.pos + 1) ?: return null) * (if (z == '-') -1 else 1)
            else -> return null
        }
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 24 || minute > 59 || second > 59) return null
        if (year > MAX_EXTENDED_YEAR || year < -MAX_EXTENDED_YEAR) return null
        return epochMs(year, month, day, hour, minute, second, millis) - offset
    }

    /**
     * ICU `TimeZoneFormat::parseOffsetISO8601` from [start] (just after the sign), in milliseconds.
     * First the extended form `H[H][:mm[:ss]]` — where, as in ICU, a `:` after a ONE-digit hour is
     * skipped, so `+1:00` is +10 h and `+2:30` is +23 h; an hour above 23 keeps only its first digit.
     * When that matched at most two characters, the basic form (up to six abutting ASCII digits,
     * `H`, `HH`, `Hmm`, `HHmm`, `Hmmss`, `HHmmss`, longest valid first) wins if it is longer:
     * `+0230` is 2:30, `+023` is 0:23.
     */
    internal fun offsetMs(s: String, start: Int): Long? {
        fun ascii(i: Int): Int = if (i < s.length && s[i] in '0'..'9') s[i] - '0' else -1
        val value = intArrayOf(0, 0, 0)
        val length = intArrayOf(0, -1, -1)
        var field = 0
        var i = start
        while (i < s.length && field <= 2) {
            val ch = s[i]
            if (ch == ':') {
                if (field == 0) {
                    if (length[0] == 0) break
                } else {
                    if (length[field] != -1) break
                    length[field] = 0
                }
                i++
                continue
            } else if (length[field] == -1) {
                break
            }
            val d = ascii(i)
            if (d < 0) break
            value[field] = value[field] * 10 + d
            length[field]++
            if (length[field] >= 2) field++
            i++
        }
        if (length[0] == 0) return null
        var result: Long
        var parsed: Int
        if (value[0] > 23) {
            result = (value[0] / 10) * HOUR
            parsed = 1
        } else {
            result = value[0] * HOUR
            parsed = length[0]
            if (length[1] == 2 && value[1] <= 59) {
                result += value[1] * MINUTE
                parsed += 1 + length[1]
                if (length[2] == 2 && value[2] <= 59) {
                    result += value[2] * SECOND
                    parsed += 1 + length[2]
                }
            }
        }
        if (parsed <= 2) {
            val digits = IntArray(6)
            var n = 0
            while (n < 6 && ascii(start + n) >= 0) {
                digits[n] = ascii(start + n)
                n++
            }
            while (n >= 1) {
                var h = 0; var m = 0; var sec = 0
                when (n) {
                    1 -> h = digits[0]
                    2 -> h = digits[0] * 10 + digits[1]
                    3 -> { h = digits[0]; m = digits[1] * 10 + digits[2] }
                    4 -> { h = digits[0] * 10 + digits[1]; m = digits[2] * 10 + digits[3] }
                    5 -> { h = digits[0]; m = digits[1] * 10 + digits[2]; sec = digits[3] * 10 + digits[4] }
                    else -> { h = digits[0] * 10 + digits[1]; m = digits[2] * 10 + digits[3]; sec = digits[4] * 10 + digits[5] }
                }
                if (h <= 23 && m <= 59 && sec <= 59) {
                    if (n > parsed) result = h * HOUR + m * MINUTE + sec * SECOND
                    break
                }
                n--
            }
        }
        return result
    }

    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L
    private const val SECOND = 1_000L
    /**
     * Beyond about ±5.8 million years (ICU's calendar range) iOS returns a wrapped, meaningless date
     * (`9999999-05-07T10:00:00Z` comes back about 1.76 million years BC); Android answers null there.
     */
    private const val MAX_EXTENDED_YEAR = 5_900_000L

    private fun utcCalendar() = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        clear()
        isLenient = true
    }

    /** [year] is an extended year (0 = 1 BC); [day] / [hour] may overflow and roll over. */
    private fun epochMs(year: Long, month: Long, day: Long, hour: Long, minute: Long, second: Long, millis: Long): Long {
        val cal = utcCalendar()
        if (year <= 0) {
            cal.set(Calendar.ERA, GregorianCalendar.BC)
            cal.set(Calendar.YEAR, (1 - year).toInt())
        } else {
            cal.set(Calendar.YEAR, year.toInt())
        }
        cal.set(Calendar.MONTH, month.toInt() - 1)
        cal.set(Calendar.DAY_OF_MONTH, day.toInt())
        return cal.timeInMillis + hour * HOUR + minute * MINUTE + second * SECOND + millis
    }

    private class Cursor(val s: String) {
        var pos = 0
        val atEnd get() = pos >= s.length
        val peek get() = s[pos]

        fun skipWhitespace() {
            while (!atEnd && (Character.isWhitespace(peek) || Character.isSpaceChar(peek))) pos++
        }

        /** Whitespace, then one or more Unicode decimal digits. */
        fun number(): Long? {
            skipWhitespace()
            val start = pos
            var v = 0L
            while (!atEnd) {
                val d = Character.digit(peek, 10)
                if (d < 0) break
                if (v < 1_000_000_000L) v = v * 10 + d
                pos++
            }
            return if (pos == start) null else v
        }

        fun dateSeparator(): Boolean {
            val start = pos
            skipWhitespace()
            if (!atEnd && peek in "-./") {
                pos++
                return true
            }
            return pos > start
        }

        fun timeSeparator(): Boolean {
            skipWhitespace()
            if (!atEnd && peek in ":.") {
                pos++
                return true
            }
            return false
        }

        /**
         * Between the zone-less reader's time fields iOS's `DateFormatter` takes `:`, `.` or `-`, each
         * optionally preceded by whitespace, or whitespace alone (measured) — not `/`, `,` or `_`.
         */
        fun zonelessTimeSeparator(): Boolean {
            val start = pos
            skipWhitespace()
            if (!atEnd && peek in ":.-") {
                pos++
                return true
            }
            return pos > start
        }

        /** Consumes the rest; true when it was only Unicode space separators and tabs (not a newline). */
        fun onlySpacesOrTabsLeft(): Boolean {
            while (!atEnd) {
                val ch = peek
                if (Character.getType(ch) != Character.SPACE_SEPARATOR.toInt() && ch != '\t') return false
                pos++
            }
            return true
        }

        fun date(signedYear: Boolean): Triple<Long, Long, Long>? {
            var sign = 1L
            if (signedYear && !atEnd && peek == '-') {
                sign = -1L
                pos++
            }
            val rawYear = number() ?: return null
            if (!dateSeparator()) return null
            val month = number() ?: return null
            if (!dateSeparator()) return null
            val day = number() ?: return null
            val year = if (rawYear in 12_000L..12_099L) {
                val yy = rawYear - 12_000L
                if (yy < 50) 2000 + yy else 1900 + yy
            } else rawYear
            return Triple(sign * year, month, day)
        }
    }
}
