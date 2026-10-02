package ai.appdna.sdk.core

import ai.appdna.sdk.messages.MessageManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/**
 * Android reads dates exactly as iOS does, on every API level. `java.time` is API 26 and minSdk is 24;
 * the old reader used `OffsetDateTime` on API 26+ and `SimpleDateFormat` on API 24/25, and the two
 * paths disagreed with each other and with iOS (`2026-5-7T10:00:00Z` parsed only on API 24/25, a
 * surrounding space was trimmed on Android but not on iOS, `+02` and a lowercase `z` failed on API 26+,
 * a 5-digit year, Arabic-Indic digits and NBSP differed …).
 *
 * `ios-date-corpus.tsv` holds iOS's MEASURED answers (iOS 26.2 simulator; macOS Foundation identical)
 * for 319 inputs — `ISO8601.date(from:)` and `MessageManager.parseWindowDate`. Every one is asserted
 * here at API 24 and at API 33, and so is the shared fixture `dto_parsing/message_window_date_parsing`
 * (which iOS asserts too).
 * NEGATIVE CONTROL: with the previous `IsoInstant` (java.time / SimpleDateFormat) both API levels fail
 * the corpus and the fixture (e.g. `2026-5-7T10:00:00Z`, `2026-05-07T12:00:00+02`, `٢٠٢٦-٠٥-٠٧`).
 */
@RunWith(RobolectricTestRunner::class)
class IsoInstantTest {

    private val cases = mapOf(
        "2026-05-07T10:00:00Z" to 1778148000000L,
        "2026-05-07T10:00:00.250Z" to 1778148000250L,
        "2026-05-07T12:00:00+02:00" to 1778148000000L,
        "2026-05-07T12:00:00.250+02:00" to 1778148000250L,
        "2026-05-07T10:00:00.250123Z" to 1778148000250L,
    )

    private fun assertCanonical() {
        for ((iso, ms) in cases) assertEquals(iso, ms, IsoInstant.parseEpochMs(iso))
        assertNull(IsoInstant.parseEpochMs("2026-05-07"))
        assertNull(IsoInstant.parseEpochMs("2026-05-07T10:00:00"))
        assertNull(IsoInstant.parseEpochMs("not a date"))
        assertNull(IsoInstant.parseEpochMs(""))
    }

    private fun ms(s: String): Long? = if (s == "null") null else s.toLong()

    private fun assertIosCorpus() {
        val text = javaClass.classLoader!!.getResourceAsStream("ios-date-corpus.tsv")!!.bufferedReader().readText()
        val mismatches = mutableListOf<String>()
        var n = 0
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (json, window, instant) = line.split('\t')
            val value = JSONArray("[$json]").getString(0)
            val gotInstant = IsoInstant.parseEpochMs(value)
            val gotWindow = gotInstant ?: IsoInstant.parseDateOnlyUtcMs(value)
            if (gotInstant != ms(instant)) mismatches += "instant $json: iOS=$instant android=$gotInstant"
            if (gotWindow != ms(window)) mismatches += "window $json: iOS=$window android=$gotWindow"
            n++
        }
        assertEquals(319, n)
        assertTrue("Android differs from iOS:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    /** The shared fixture's cases through the REAL `MessageManager.parseWindowDate`. */
    private fun assertSharedFixture() {
        val f = JSONObject(fixtureFile().readText())
        val action = f.getJSONObject("action")
        val cases = action.getJSONArray("cases")
        val expected = f.getJSONObject("expect").getJSONObject("state_after").getJSONArray("results")
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(action.getString("device_timezone")))
        try {
            val mm = MessageManager(ApplicationProvider.getApplicationContext(), { emptyMap() })
            for (i in 0 until cases.length()) {
                val value = cases.getJSONObject(i).getString("value")
                val want = if (expected.isNull(i)) null else expected.getLong(i)
                assertEquals("case $i ${JSONObject.quote(value)}", want, mm.parseWindowDate(value)?.time)
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private fun fixtureFile(id: String = "message_window_date_parsing"): File {
        val rel = "packages/sdk-shared-fixtures/dto_parsing/$id.fixture.json"
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { dir ->
            File(dir, "dto_parsing/$id.fixture.json").takeIf { it.isFile }?.let { return it }
        }
        var here: File? = File(".").canonicalFile
        repeat(10) {
            val candidate = File(here, rel)
            if (candidate.isFile) return candidate
            here = here?.parentFile
        }
        error("Could not locate $rel. Set APPDNA_SDK_FIXTURES_DIR.")
    }

    /**
     * The countdown's `target_datetime` and a form date field's `min_date` / `max_date`, against iOS's
     * MEASURED answers (`ios-config-date-corpus.tsv`: macOS Foundation `DateFormatter`, the two readers'
     * exact configurations). NEGATIVE CONTROL: the previous `SimpleDateFormat` readers read the prefix of
     * `2026-12-31T23:59:59Z`, rolled `2026-02-30` over and rejected `٢٠٢٦-…` and `T23 59 59`.
     */
    private fun assertConfigDateCorpus() {
        val text = javaClass.classLoader!!.getResourceAsStream("ios-config-date-corpus.tsv")!!.bufferedReader().readText()
        val mismatches = mutableListOf<String>()
        var n = 0
        for (line in text.split('\n')) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (kind, zone, json, want) = line.split('\t')
            val value = JSONArray("[$json]").getString(0)
            val got = when (kind) {
                "countdown" -> IsoInstant.parseZonelessUtcMs(value)
                "dateonly", "dateonly_lmt" -> IsoInstant.parseDateOnlyLocalMs(value, TimeZone.getTimeZone(zone))
                else -> error("unknown kind $kind")
            }
            val ios = ms(want)
            val same = if (kind == "dateonly_lmt") {
                // Before the zone had standard time iOS's tz data applies its local mean time and the JVM's
                // does not: the same day, under an hour apart (see the corpus header).
                got != null && ios != null && got != ios && kotlin.math.abs(got - ios) < 3_600_000L
            } else {
                got == ios
            }
            if (!same) mismatches += "$kind $zone $json: iOS=$want android=$got"
            n++
        }
        assertEquals(201, n)
        assertTrue("Android differs from iOS:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    /** `countdown_target_datetime` / `form_date_bounds_parsing` through the REAL Android functions. */
    private fun assertConfigDateFixtures() {
        val cd = JSONObject(fixtureFile("countdown_target_datetime").readText())
        val cdAction = cd.getJSONObject("action")
        val cdCases = cdAction.getJSONArray("cases")
        val cdWant = cd.getJSONObject("expect").getJSONObject("state_after").getJSONArray("results")
        for (i in 0 until cdCases.length()) {
            val c = cdCases.getJSONObject(i)
            val got = ai.appdna.sdk.onboarding.countdownInitialSeconds(
                if (c.isNull("target_type")) null else c.getString("target_type"),
                if (c.isNull("target_datetime")) null else c.getString("target_datetime"),
                if (c.isNull("duration_seconds")) null else c.getInt("duration_seconds"),
                cdAction.getLong("now_ms"),
            )
            assertEquals("countdown case $i $c", cdWant.getInt(i), got)
        }
        val fd = JSONObject(fixtureFile("form_date_bounds_parsing").readText())
        val fdAction = fd.getJSONObject("action")
        val fdCases = fdAction.getJSONArray("cases")
        val fdWant = fd.getJSONObject("expect").getJSONObject("state_after").getJSONArray("results")
        val zone = TimeZone.getTimeZone(fdAction.getString("device_timezone"))
        for (i in 0 until fdCases.length()) {
            val c = fdCases.getJSONObject(i)
            val (lo, hi) = ai.appdna.sdk.onboarding.formDateBounds(
                if (c.isNull("min_date")) null else c.getString("min_date"),
                if (c.isNull("max_date")) null else c.getString("max_date"),
                zone,
            )
            val w = fdWant.getJSONArray(i)
            assertEquals("form case $i $c min", if (w.isNull(0)) null else w.getLong(0), lo)
            assertEquals("form case $i $c max", if (w.isNull(1)) null else w.getLong(1), hi)
        }
    }

    @Test @Config(sdk = [24]) fun `api 24 - countdown and form dates, every iOS-measured answer`() = assertConfigDateCorpus()

    @Test @Config(sdk = [33]) fun `api 33 - countdown and form dates, every iOS-measured answer`() = assertConfigDateCorpus()

    @Test @Config(sdk = [24]) fun `api 24 - shared fixtures countdown_target_datetime and form_date_bounds_parsing`() = assertConfigDateFixtures()

    @Test @Config(sdk = [33]) fun `api 33 - shared fixtures countdown_target_datetime and form_date_bounds_parsing`() = assertConfigDateFixtures()

    /**
     * Round 28 — the range edge, MEASURED on iOS (26.2 simulator = macOS 26.3 Foundation, 2026-10-02). iOS keeps
     * the date as a Double of seconds, so its value at this magnitude is within 32 ms of the exact one.
     * NEGATIVE CONTROL: with the old `year <= 5_900_000` cut-off, the nil rows below (06-04, 24:00 roll-over,
     * 07-01, the rest of 5874898, 5874899…, and 01-01 / 03-02 of -5877520) answer a date and this fails.
     */
    private fun assertMeasuredEdge() {
        val iosValue = mapOf(
            "5874898-06-03T23:59:59Z" to 185331720383999008L,
            "5874898-06-03T23:00:00-02:00" to 185331720387600000L,
            "5874898-06-03T12:00:00Z" to 185331720340800000L,
            "5874898-05-07T10:00:00Z" to 185331718000800000L,
            "5874897-05-07T10:00:00Z" to 185331686464800000L,
            "5874898-06-03T23:59:59.500Z" to 185331720383999488L,
            "-5877520-03-03T00:00:00Z" to -185542587187200000L,
            "-5877520-05-07T10:00:00Z" to -185542581535200000L,
            "-5877520-12-31T23:59:59Z" to -185542560921600992L,
            "-5877519-12-31T23:59:59-01:00" to -185542529382000992L,
        )
        for ((iso, ios) in iosValue) {
            val got = IsoInstant.parseEpochMs(iso)
            assertTrue("$iso: iOS=$ios android=$got", got != null && kotlin.math.abs(got - ios) <= 32)
        }
        for (iso in listOf(
            "5874898-06-04T00:00:00Z", "5874898-06-03T24:00:00Z", "5874898-06-04T01:00:00+02:00",
            "5874898-06-31T00:00:00Z", "5874898-07-01T00:00:00Z", "5874898-12-31T23:59:59Z",
            "5874899-01-01T00:00:00Z", "5880000-01-01T00:00:00Z", "-5877520-01-01T00:00:00Z",
            "-5877520-03-02T23:59:59Z", "-5877520-01-01T00:30:00+01:00", "-5880000-05-07T10:00:00Z",
            "5874898-06-04T00:00:00.000Z",
        )) assertNull("iOS answers nil for $iso", IsoInstant.parseEpochMs(iso))
        // iOS answers a wrapped, meaningless date here; Android answers nil (documented, not reproduced).
        assertNull(IsoInstant.parseEpochMs("5899999-05-07T10:00:00Z"))
        assertNull(IsoInstant.parseEpochMs("-5899999-05-07T10:00:00Z"))
    }

    @Test @Config(sdk = [24]) fun `api 24 - the range edge iOS was measured at`() = assertMeasuredEdge()

    @Test @Config(sdk = [33]) fun `api 33 - the range edge iOS was measured at`() = assertMeasuredEdge()

    @Test @Config(sdk = [24]) fun `api 24 - canonical instants`() = assertCanonical()

    @Test @Config(sdk = [33]) fun `api 33 - canonical instants`() = assertCanonical()

    @Test @Config(sdk = [24]) fun `api 24 - every iOS-measured answer`() = assertIosCorpus()

    @Test @Config(sdk = [33]) fun `api 33 - every iOS-measured answer`() = assertIosCorpus()

    @Test @Config(sdk = [24]) fun `api 24 - shared fixture message_window_date_parsing`() = assertSharedFixture()

    @Test @Config(sdk = [33]) fun `api 33 - shared fixture message_window_date_parsing`() = assertSharedFixture()
}
