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

    private fun fixtureFile(): File {
        val rel = "packages/sdk-shared-fixtures/dto_parsing/message_window_date_parsing.fixture.json"
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { dir ->
            File(dir, "dto_parsing/message_window_date_parsing.fixture.json").takeIf { it.isFile }?.let { return it }
        }
        var here: File? = File(".").canonicalFile
        repeat(10) {
            val candidate = File(here, rel)
            if (candidate.isFile) return candidate
            here = here?.parentFile
        }
        error("Could not locate $rel. Set APPDNA_SDK_FIXTURES_DIR.")
    }

    @Test @Config(sdk = [24]) fun `api 24 - canonical instants`() = assertCanonical()

    @Test @Config(sdk = [33]) fun `api 33 - canonical instants`() = assertCanonical()

    @Test @Config(sdk = [24]) fun `api 24 - every iOS-measured answer`() = assertIosCorpus()

    @Test @Config(sdk = [33]) fun `api 33 - every iOS-measured answer`() = assertIosCorpus()

    @Test @Config(sdk = [24]) fun `api 24 - shared fixture message_window_date_parsing`() = assertSharedFixture()

    @Test @Config(sdk = [33]) fun `api 33 - shared fixture message_window_date_parsing`() = assertSharedFixture()
}
