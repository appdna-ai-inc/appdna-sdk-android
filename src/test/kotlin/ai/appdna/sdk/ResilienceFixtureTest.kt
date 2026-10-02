package ai.appdna.sdk

import ai.appdna.sdk.events.EventQueue
import ai.appdna.sdk.events.EventUploadCoordinator
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

                    // The pause holds for the OS background uploader: drives the real `onAppBackground` of a real
                    // queue (paused / not) and the real `EventUploadWorker.uploadPending` with the gate it reads.
                    val os = block.getJSONObject("os_upload")
                    val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                    for (paused in listOf(true, false)) {
                        val scheduled = java.util.concurrent.atomic.AtomicInteger(0)
                        val db = EventDatabase(ctx, 10_000, "pause_os_${System.nanoTime()}.db")
                        val q = EventQueue(
                            ApiClient("adn_test_pause_os", "http://127.0.0.1:9"), db, null, 100, Long.MAX_VALUE, ctx,
                            object : ai.appdna.sdk.events.BackgroundUploadScheduler {
                                override fun scheduleIfNeeded() { scheduled.incrementAndGet() }
                            },
                        )
                        if (paused) q.pauseForTest()
                        q.onAppBackground()
                        val deadline = System.currentTimeMillis() + 2_000
                        while (System.currentTimeMillis() < deadline && scheduled.get() == 0) Thread.sleep(20)
                        assertEquals("[$id] does backgrounding schedule the background upload (paused=$paused)?",
                            os.getBoolean(if (paused) "background_schedules_when_paused" else "background_schedules_when_unpaused"),
                            scheduled.get() > 0)
                        db.insertEvent(JSONObject().put("event_id", "os-${System.nanoTime()}").put("event_name", "e")
                            .put("ts_ms", System.currentTimeMillis()).toString())
                        val outcome = ai.appdna.sdk.background.EventUploadWorker.uploadPending(
                            db, "adn_test_pause_os", "http://127.0.0.1:9",
                            ai.appdna.sdk.events.UploadPauseGate.isPaused(ctx),
                        )
                        // Paused: done at once, the event untouched. Not paused: it tries (the closed port → retry).
                        val uploaded = outcome != ai.appdna.sdk.background.EventUploadWorker.Outcome.DONE
                        assertEquals("[$id] does a background run upload (paused=$paused)?",
                            os.getBoolean(if (paused) "worker_uploads_when_paused" else "worker_uploads_when_unpaused"),
                            uploaded)
                        assertEquals(1, db.count())
                        db.clearAll()
                        q.shutdown()
                        q.awaitShutdownFlush(10_000)
                    }
                    ai.appdna.sdk.events.UploadPauseGate.setForTest(ctx, false)
                }

                // The retry loop of a failed bootstrap: its backoff schedule and bound, and — driving the real
                // `BootstrapRecovery` with a long backoff, so only a trigger can start an attempt — that an
                // attempt is made only for a trigger while online.
                "bootstrap_recovery" -> {
                    assertEquals("[$id] max_attempts", block.getInt("max_attempts"), BootstrapRecovery.DEFAULT_MAX_ATTEMPTS)
                    assertEquals("[$id] jitter_pct", block.getDouble("jitter_pct"), BootstrapRecovery.JITTER_PCT, 0.0)
                    assertEquals("[$id] trigger_delay_max_ms", block.getLong("trigger_delay_max_ms"), BootstrapRecovery.DEFAULT_TRIGGER_DELAY_MAX_MS)
                    val pct = block.getDouble("jitter_pct")
                    // The jitter spans exactly ±pct of the base, and is not a constant.
                    assertEquals("[$id] lowest jitter", (10_000 * (1 - pct)).toLong(), BootstrapRecovery.jittered(10_000, 0.0))
                    assertEquals("[$id] middle jitter", 10_000L, BootstrapRecovery.jittered(10_000, 0.5))
                    assertTrue("[$id] highest jitter", BootstrapRecovery.jittered(10_000, 0.999_999) <= (10_000 * (1 + pct)).toLong())
                    fun outcome(status: Int, retryAfter: Long?) =
                        BootstrapRecovery.outcomeForFailure(if (status == 0) null else status, retryAfter)
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val retryAfter = if (c.has("retry_after_s")) c.getLong("retry_after_s") else null
                        if (c.has("attempt_index")) {
                            val index = c.getInt("attempt_index")
                            assertEquals("[$id] backoff before attempt ${index + 1}", c.getLong("backoff_ms"),
                                BootstrapRecovery.defaultBackoffMs(index))
                        } else if (c.has("online")) {
                            val online = c.getBoolean("online")
                            val triggers = if (c.has("triggers")) c.getInt("triggers") else if (c.optBoolean("triggered")) 1 else 0
                            val answer = if (c.has("failure_status")) outcome(c.getInt("failure_status"), retryAfter) else BootstrapRecovery.Outcome.Done
                            val want = c.getInt("attempts_made")
                            val made = java.util.concurrent.atomic.AtomicInteger(0)
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                            val recovery = BootstrapRecovery(isOnline = { online }, backoffMs = { 3_600_000L },
                                random = { 0.0 }, triggerDelayMaxMs = 0L)
                            recovery.start(scope) { made.incrementAndGet(); answer }
                            for (t in 0 until triggers) {
                                recovery.trigger()
                                val deadline = System.currentTimeMillis() + 500
                                while (System.currentTimeMillis() < deadline && made.get() <= t) Thread.sleep(20)
                            }
                            Thread.sleep(100)
                            recovery.stop()
                            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                            assertEquals("[$id] attempts with online=$online triggers=$triggers failure=${c.opt("failure_status")}", want, made.get())
                        } else if (c.has("failure_status")) {
                            val status = c.getInt("failure_status")
                            val got = when (val o = outcome(status, retryAfter)) {
                                BootstrapRecovery.Outcome.Stop -> "stop"
                                BootstrapRecovery.Outcome.Retry -> "retry"
                                BootstrapRecovery.Outcome.Done -> "done"
                                is BootstrapRecovery.Outcome.RetryAfter -> {
                                    assertEquals("[$id] the Retry-After honoured after $status", retryAfter!! * 1000L, o.ms)
                                    "retry_after"
                                }
                            }
                            assertEquals("[$id] what follows a failed attempt with $status", c.getString("recovery"), got)
                        } else {
                            fail("[$id] bootstrap_recovery case needs attempt_index+backoff_ms, online+attempts_made, or failure_status+recovery")
                        }
                    }
                }

                // The process-wide registry every upload owner records its resolved event ids in (and a queue
                // consults before it uploads). Drives the real `EventUploadCoordinator`; cases run in order.
                "resolved_events_not_resent" -> {
                    assertEquals("[$id] max_resolved", block.getInt("max_resolved"), EventUploadCoordinator.MAX_RESOLVED)
                    EventUploadCoordinator.clearResolvedForTest()
                    try {
                        for (i in 0 until cases.length()) {
                            val c = cases.getJSONObject(i)
                            val mark = c.optJSONArray("mark")
                            EventUploadCoordinator.markResolved((0 until (mark?.length() ?: 0)).map { mark!!.getString(it) })
                            assertEquals("[$id] is '${c.getString("query")}' resolved?", c.getBoolean("resolved"),
                                EventUploadCoordinator.wasResolved(c.getString("query")))
                        }
                        // The bound: the oldest id leaves once max_resolved newer ones are recorded.
                        val max = block.getInt("max_resolved")
                        EventUploadCoordinator.clearResolvedForTest()
                        EventUploadCoordinator.markResolved((0..max).map { "bound-$it" })
                        assertTrue("[$id] the registry is not bounded", !EventUploadCoordinator.wasResolved("bound-0"))
                        assertTrue(EventUploadCoordinator.wasResolved("bound-$max"))
                        // A real queue: it loaded these events from its store; another owner then resolved some
                        // of them; its next upload sends only the rest.
                        assertQueueDoesNotResend(id, block.getJSONObject("queue"))
                    } finally {
                        EventUploadCoordinator.clearResolvedForTest()
                    }
                }

                // A flush's store work parses no stored row: the prune and the removal are SQL on columns.
                "flush_cost_bounded" -> assertFlushCostBounded(id, block.getJSONObject("cost"))

                else -> fail("[$id] unknown resilience contract '$contract' — this runner must assert it, never skip it")
            }
        }

        // Every contract the schema defines must actually be exercised, or a fixture could be deleted
        // and this suite would still go green on the survivors.
        assertEquals(
            "every resilience contract must be covered by a fixture",
            setOf("transient_status", "retry_after", "stale_horizon", "permanent_failure", "backoff", "flush_pause_gate",
                "bootstrap_recovery", "resolved_events_not_resent", "flush_cost_bounded"),
            seenContracts,
        )
    }

    private fun assertFlushCostBounded(id: String, cost: JSONObject) {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val backlogs = cost.getJSONArray("backlogs").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val stale = cost.getInt("stale")
        val batch = cost.getInt("batch")
        val flushes = cost.getInt("flushes")
        ai.appdna.sdk.events.DroppedEventsCounter.init(ctx)
        val saved = ai.appdna.sdk.events.DroppedEventsCounter.getAndReset()
        try {
            for (backlog in backlogs) {
                ai.appdna.sdk.events.DroppedEventsCounter.getAndReset()
                val db = EventDatabase(ctx, 10_000, "flush_cost_${System.nanoTime()}.db")
                try {
                    val now = System.currentTimeMillis()
                    val old = now - EventDatabase.REDELIVERY_HORIZON_MS - 60_000
                    fun ev(name: String, ts: Long) = JSONObject().put("event_id", "$name-${System.nanoTime()}")
                        .put("event_name", name).put("ts_ms", ts).put("properties", JSONObject().put("k", "v")).toString()
                    db.insertEvents((0 until stale).map { ev("stale_$it", old) } + (0 until backlog).map { ev("e_$it", now) })
                    assertEquals("[$id] seeded", stale + backlog, db.count())
                    val before = db.jsonParsesForTest
                    var returned = 0
                    var pruned = 0
                    repeat(flushes) {
                        pruned += db.pruneStale(EventDatabase.REDELIVERY_HORIZON_MS)
                        val rows = db.loadBatch(batch)
                        returned += rows.size
                        // The queue knows its batch's event ids (it holds the events); the runner reads them here.
                        db.removeByEventIds(rows.map { JSONObject(it.second).getString("event_id") }.toSet())
                    }
                    val extra = db.jsonParsesForTest - before
                    assertEquals("[$id] backlog $backlog: rows parsed by the store during the flushes",
                        cost.getInt("extra_lines_decoded"), extra)
                    assertEquals("[$id] backlog $backlog: stale events pruned", stale, pruned)
                    assertEquals("[$id] backlog $backlog: the prune is counted", stale,
                        ai.appdna.sdk.events.DroppedEventsCounter.peek())
                    assertEquals("[$id] backlog $backlog: batches taken", batch * flushes, returned)
                    assertEquals("[$id] backlog $backlog: events left", backlog - batch * flushes, db.count())
                } finally {
                    db.clearAll()
                    db.close()
                }
            }
        } finally {
            ai.appdna.sdk.events.DroppedEventsCounter.getAndReset()
            if (saved > 0) ai.appdna.sdk.events.DroppedEventsCounter.increment(saved)
        }
    }

    private fun assertQueueDoesNotResend(id: String, row: JSONObject) {
        fun strings(key: String) = row.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        EventUploadCoordinator.clearResolvedForTest()
        ai.appdna.sdk.network.TestHttpServer.settleIoDispatcher()
        val server = ai.appdna.sdk.network.TestHttpServer { _, _ -> 200 to "{}" }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = EventDatabase(ctx, 10_000, "resolved_queue_${System.nanoTime()}.db")
        try {
            for (name in strings("loaded")) {
                db.insertEvent(JSONObject().put("event_id", name).put("event_name", "resolved_queue")
                    .put("ts_ms", System.currentTimeMillis()).toString())
            }
            val q = EventQueue(ApiClient("adn_test_resolved_queue", server.baseUrl), db, null, 100, Long.MAX_VALUE, ctx, null)
            EventUploadCoordinator.markResolved(strings("resolved_elsewhere"))
            q.flush()
            val deadline = System.currentTimeMillis() + 10_000
            fun ingests() = synchronized(server.rawBodies) { server.rawBodies.filter { it.first.startsWith("/api/v1/ingest/events") } }
            while (System.currentTimeMillis() < deadline && ingests().isEmpty()) Thread.sleep(20)
            Thread.sleep(300)
            val sent = ingests().flatMap { (_, raw) ->
                val json = java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(raw), java.util.zip.Inflater(true))
                    .readBytes().toString(Charsets.UTF_8)
                val batch = JSONObject(json).getJSONArray("batch")
                (0 until batch.length()).map { batch.getJSONObject(it).getString("event_id") }
            }
            assertEquals("[$id] the queue sent events another owner had already resolved", strings("sent").sorted(), sent.sorted())
            q.shutdown()
            q.awaitShutdownFlush(10_000)
        } finally {
            server.close()
            EventUploadCoordinator.clearResolvedForTest()
        }
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
