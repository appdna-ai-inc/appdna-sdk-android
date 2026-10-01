package ai.appdna.sdk.core

import android.os.Build
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses an ISO-8601 instant that carries its zone — `2026-05-07T10:00:00Z`,
 * `2026-05-07T10:00:00.000Z`, `2026-05-07T12:00:00+02:00` — to epoch milliseconds, or null.
 *
 * `java.time` is API 26 and the SDK's minSdk is 24. The callers used `java.time.OffsetDateTime` /
 * `Instant` directly and caught the `NoClassDefFoundError` on Android 7, then fell back to a
 * zone-less `SimpleDateFormat` — which read the instant in the device's time zone (hours off) or
 * kept only the date. Below API 26 this parses the same shapes with `SimpleDateFormat`'s `XXX`
 * zone pattern (API 24).
 */
internal object IsoInstant {

    fun parseEpochMs(iso: String): Long? {
        val s = iso.trim()
        if (s.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { null }
            }
        }
        return parseLegacy(s)
    }

    private val FRACTION = Regex("""\.(\d+)""")
    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mmXXX",
    )

    /** The pre-API-26 path. `SimpleDateFormat` reads exactly three fraction digits, so normalise them. */
    internal fun parseLegacy(iso: String): Long? {
        val s = FRACTION.replace(iso) { m -> "." + m.groupValues[1].padEnd(3, '0').take(3) }
        for (pattern in PATTERNS) {
            val f = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val pos = ParsePosition(0)
            val date = f.parse(s, pos)
            if (date != null && pos.index == s.length) return date.time
        }
        return null
    }
}
