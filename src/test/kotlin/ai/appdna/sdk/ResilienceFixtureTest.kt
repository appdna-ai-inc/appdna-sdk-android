package ai.appdna.sdk

import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.EventUploadDisposition
import ai.appdna.sdk.storage.EventDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SPEC-070-B AC-35 — resilience behavioral fixtures (packages/sdk-shared-fixtures/resilience/).
 *
 * These are the upload/queue SURVIVAL contracts, and every one of them lives below the bridge:
 * a wrapper cannot observe an HTTP status, a `Retry-After` header, or a prune decision. So — exactly
 * like `events` — the category is native-only and iOS + Android assert the SAME fixture table.
 *
 * Each `contract` maps to a PURE seam, which is why this can be a table test rather than an HTTP mock:
 *   - transient_status → [ApiClient.TRANSIENT_STATUS_CODES]  (iOS: APIClient.transientStatusCodes)
 *   - retry_after      → [ApiClient.parseRetryAfter]         (iOS: APIClient.parseRetryAfter)
 *   - stale_horizon    → [EventDatabase.isStale]             (iOS: EventStore.isStale)
 *
 * A fixture whose `contract` this runner does not know FAILS. It is never skipped — a silently
 * skipped resilience fixture is the coverage theater AC-35 exists to remove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResilienceFixtureTest {

    /** Constructed only to reach `parseRetryAfter`; it performs no I/O. */
    private val api = ApiClient(apiKey = "adn_test_placeholder", environment = Environment.SANDBOX)

    @Test
    fun resilienceFixtures() {
        val fixtures = loadResilienceFixtures()
        assertTrue(
            "No resilience fixtures found — the category must not silently vanish",
            fixtures.isNotEmpty(),
        )

        val seenContracts = mutableSetOf<String>()
        for ((id, json) in fixtures) {
            val block = json.getJSONObject("resilience")
            val contract = block.getString("contract")
            seenContracts.add(contract)
            // Lazy: `backoff` asserts a DISTRIBUTION and carries no case table, so eagerly reading
            // `cases` would throw before the dispatch below ever ran.
            val cases: org.json.JSONArray by lazy { block.getJSONArray("cases") }

            when (contract) {
                "transient_status" -> {
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val status = c.getInt("status")
                        val expected = c.getBoolean("transient")
                        assertEquals(
                            "[$id] HTTP $status transient?",
                            expected,
                            status in ApiClient.TRANSIENT_STATUS_CODES,
                        )
                    }
                }

                "retry_after" -> {
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        // `isNull` distinguishes a JSON null (header absent) from the string "null".
                        val header: String? = if (c.isNull("header")) null else c.getString("header")
                        val expected: Long? = if (c.isNull("seconds")) null else c.getLong("seconds")
                        assertEquals(
                            "[$id] Retry-After ${header?.let { "\"$it\"" } ?: "(absent)"}",
                            expected,
                            api.parseRetryAfter(header),
                        )
                    }
                }

                "stale_horizon" -> {
                    val horizon = block.getLong("horizon_ms")
                    // A fixed `now` keeps the table exact: age is what varies, not the wall clock.
                    val now = 1_800_000_000_000L
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val ageMs = c.getLong("age_ms")
                        val expected = c.getBoolean("stale")
                        assertEquals(
                            "[$id] age ${ageMs}ms stale?",
                            expected,
                            EventDatabase.isStale(tsMs = now - ageMs, nowMs = now, horizonMs = horizon),
                        )
                    }
                }

                // AC-35 — the full three-way classification, plus the latch expectation. Android has
                // no `eventUploadPermanentlyFailed` flag (it drops the batch and pauses via
                // bumpFailureCounter), so the `latch` sequence is asserted against the SAME
                // classifier, carried forward — a status that is not DROP_PERMANENT may neither set
                // nor clear the latched state, and only a SUCCESS clears it. That is exactly what
                // iOS's real flag does, so the two platforms are held to one expectation.
                "permanent_failure" -> {
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val status = c.getInt("status")
                        val expected = when (c.getString("disposition")) {
                            "success" -> EventUploadDisposition.SUCCESS
                            "retry_transient" -> EventUploadDisposition.RETRY_TRANSIENT
                            "drop_permanent" -> EventUploadDisposition.DROP_PERMANENT
                            // `error`, not `fail`: JUnit's fail() returns Unit, which widens the
                            // `when` to Any and quietly un-types the comparison below.
                            else -> error("[$id] unknown disposition '${c.getString("disposition")}'")
                        }
                        assertEquals(
                            "[$id] HTTP $status disposition",
                            expected,
                            ApiClient.dispositionFor(status),
                        )
                    }

                    val latch = block.optJSONArray("latch")
                        ?: fail("[$id] permanent_failure must state a `latch` sequence — the 429 defect WAS the latch")
                    var latched = false
                    for (i in 0 until (latch as org.json.JSONArray).length()) {
                        val step = latch.getJSONObject(i)
                        val status = step.getInt("status")
                        latched = when (ApiClient.dispositionFor(status)) {
                            EventUploadDisposition.SUCCESS -> false
                            EventUploadDisposition.DROP_PERMANENT -> true
                            // 🔴 W1. The transient branch must leave the latch EXACTLY as it found it.
                            // iOS set it here, and one 429 stopped every upload until app restart.
                            EventUploadDisposition.RETRY_TRANSIENT -> latched
                        }
                        assertEquals(
                            "[$id] after HTTP $status, permanently-failed?",
                            step.getBoolean("permanently_failed"),
                            latched,
                        )
                    }
                }

                // AC-35 — bounded AND jittered. Both halves, because each hides the other's failure:
                // a `return base` regression satisfies every bound, and an unbounded jitter is still
                // "jittered". Drives the real `EventQueue.jittered`, not a copy of its arithmetic.
                "backoff" -> {
                    val maxRetries = block.getInt("max_retries")
                    val jitterPct = block.getDouble("jitter_pct")
                    val samples = block.getInt("samples")
                    val minDistinct = block.getInt("min_distinct_samples")
                    val maxTotal = block.getLong("max_total_backoff_ms")
                    val bases = block.getJSONArray("base_delays_ms")
                        .let { arr -> (0 until arr.length()).map { arr.getLong(it) } }

                    assertEquals("[$id] retry count", maxRetries, EventQueue.MAX_RETRIES)
                    assertEquals("[$id] jitter fraction", jitterPct, EventQueue.JITTER_PCT, 1e-9)
                    assertEquals(
                        "[$id] base backoff schedule",
                        bases,
                        EventQueue.RETRY_DELAYS_MS.toList(),
                    )

                    var worstCaseTotal = 0L
                    for (base in bases) {
                        val lo = (base * (1 - jitterPct)).toLong()
                        val hi = (base * (1 + jitterPct)).toLong()
                        val seen = mutableSetOf<Long>()
                        repeat(samples) {
                            val d = EventQueue.jittered(base)
                            assertTrue(
                                "[$id] jittered($base) = $d escaped [$lo, $hi] — the backoff is not bounded",
                                d in lo..hi,
                            )
                            assertTrue("[$id] jittered($base) = $d is negative", d >= 0)
                            seen.add(d)
                        }
                        assertTrue(
                            "[$id] jittered($base) produced only ${seen.size} distinct value(s) over " +
                                "$samples samples — the jitter is not being applied, and a throttled " +
                                "fleet will retry in lockstep",
                            seen.size >= minDistinct,
                        )
                    }
                    // "Bounded" is a claim about the TOTAL, not about one delay: initial attempt plus
                    // MAX_RETRIES retries, each at its worst-case jittered ceiling.
                    for (attempt in 1..maxRetries) {
                        val base = bases[(attempt - 1).coerceAtMost(bases.size - 1)]
                        worstCaseTotal += (base * (1 + jitterPct)).toLong()
                    }
                    assertTrue(
                        "[$id] worst-case total backoff ${worstCaseTotal}ms exceeds the ${maxTotal}ms bound",
                        worstCaseTotal <= maxTotal,
                    )
                }

                // Which flush triggers clear the paused-after-N-failures gate. Drives the real
                // `EventQueue.FlushTrigger` every flush path goes through, and the table must name every
                // trigger of the enum — a new trigger with no row fails here instead of slipping by.
                "flush_pause_gate" -> {
                    val named = mutableSetOf<EventQueue.FlushTrigger>()
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val trigger = EventQueue.FlushTrigger.valueOf(c.getString("trigger").uppercase())
                        named.add(trigger)
                        assertEquals(
                            "[$id] does a ${c.getString("trigger")} flush clear the pause?",
                            c.getBoolean("clears_pause"),
                            trigger.clearsPauseGate,
                        )
                    }
                    assertEquals("[$id] the table must name every FlushTrigger", EventQueue.FlushTrigger.values().toSet(), named)
                }

                else -> fail("[$id] unknown resilience contract '$contract' — this runner must assert it, never skip it")
            }
        }

        // Every contract the schema defines must actually be exercised, or a fixture could be deleted
        // and this suite would still go green on the survivors.
        assertEquals(
            "every resilience contract must be covered by a fixture",
            setOf("transient_status", "retry_after", "stale_horizon", "permanent_failure", "backoff", "flush_pause_gate"),
            seenContracts,
        )
    }

    private fun loadResilienceFixtures(): List<Pair<String, JSONObject>> {
        val dir = fixturesRoot()?.let { File(it, "resilience") } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".fixture.json") }
            .orEmpty()
            .sortedBy { it.path }
            .mapNotNull { file ->
                val json = JSONObject(file.readText(Charsets.UTF_8))
                if (json.getString("category") != "resilience") return@mapNotNull null
                val platforms = json.optJSONArray("platforms")
                val applies = (0 until (platforms?.length() ?: 0))
                    .any { platforms!!.getString(it) == "android" }
                if (!applies) return@mapNotNull null
                json.getString("id") to json
            }
    }

    private fun fixturesRoot(): File? {
        System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { val f = File(it); if (f.isDirectory) return f }
        var here: File? = File(".").canonicalFile
        repeat(10) {
            val candidate = File(here, "packages/sdk-shared-fixtures")
            if (candidate.isDirectory) return candidate
            here = here?.parentFile
        }
        val codespace = File("/workspaces/appdna-ai/packages/sdk-shared-fixtures")
        if (codespace.isDirectory) return codespace
        return null
    }
}
