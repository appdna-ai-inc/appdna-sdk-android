package ai.appdna.sdk

import ai.appdna.sdk.events.ClientSeqCounter
import ai.appdna.sdk.events.DroppedEventsCounter
import ai.appdna.sdk.events.EventSchema
import ai.appdna.sdk.storage.EventDatabase
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * SPEC-428 — event-pipeline behavioral fixtures (packages/sdk-shared-fixtures/events/).
 *
 * The event pipeline (EventDatabase eviction, ClientSeqCounter monotonicity, DroppedEventsCounter,
 * event_id-stable redelivery) is NATIVE-owned per ADR-001, so iOS + Android assert these fixtures in
 * full; the Flutter/RN thin wrappers forward `track()` to native and defer these guarantees to the
 * native runners.
 *
 * Replays each fixture's `pipeline.steps` against the REAL `EventDatabase` + `EventSchema.buildEnvelope`
 * (assigns `client_seq`) + `DroppedEventsCounter`, with a mock server "sink" that dedups by `event_id`,
 * then asserts the observable output. Mirrors the iOS `EventPipelineFixtureTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPipelineFixtureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private data class Step(val op: String, val name: String?, val count: Int?, val ms: Int?)
    private data class Expect(
        val ingestedCount: Int?,
        val droppedMin: Int?,
        val noDup: Boolean,
        val monotonic: Boolean,
        val liveBytesWithinQuota: Boolean = false,
        val minLiveBytesFraction: Double? = null,
        val survivorsAreNewest: Boolean = false,
        val droppedExact: Boolean = false,
    )
    private data class Fixture(val id: String, val cap: Int?, val steps: List<Step>, val expect: Expect, val maxBytes: Int? = null)

    @Test
    fun eventPipelineFixtures() {
        ClientSeqCounter.init(ctx)
        DroppedEventsCounter.init(ctx)
        val fixtures = loadEventFixtures()
        assertTrue("No Android event-pipeline fixtures found (packages/sdk-shared-fixtures/events)", fixtures.isNotEmpty())
        for (f in fixtures) runPipeline(f)
        println("[EventPipeline] asserted ${fixtures.size} fixtures")
    }

    private fun resetCounters() {
        ctx.getSharedPreferences("appdna_client_seq", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("appdna_dropped_events", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun makeEvent(name: String): String =
        EventSchema.buildEnvelope(
            eventName = name,
            properties = null,
            identity = DeviceIdentity("spec428-anon", null, null),
            sessionId = "spec428-session",
            appVersion = "1.0",
            analyticsConsent = true,
        ).toString()

    private fun runPipeline(f: Fixture) {
        resetCounters()
        val cap = f.cap ?: 10_000
        val maxBytes = f.maxBytes ?: EventDatabase.MAX_DISK_BYTES
        val dbName = "spec428_${f.id}.db"
        var db = EventDatabase(ctx, cap, dbName, maxBytes)
        db.clearAll()
        resetCounters()

        var online = true
        val ingestedIds = mutableSetOf<String>()
        val ingested = mutableListOf<JSONObject>()
        var rawSent = 0
        var hadRedeliver = false
        val tracked = mutableListOf<String>()

        fun flush() {
            if (!online) return
            val pending = db.loadAll().map { JSONObject(it) }
            rawSent += pending.size // EVERY send, including redeliveries of still-unacked events
            for (e in pending) {
                if (ingestedIds.add(e.getString("event_id"))) ingested.add(e) // server dedups by STABLE event_id
            }
            // Deliberately do NOT removeByEventIds — the queue stays "unacked" so a `redeliver` step
            // re-sends the SAME stored events (same event_id). If event_id were regenerated on resend,
            // the sink would fail to dedup → ingested_count would exceed the expected → the test fails.
        }

        for (s in f.steps) {
            when (s.op) {
                "track", "track_before_configure" -> {
                    val n = s.count ?: 1
                    val base = s.name ?: "evt"
                    for (i in 0 until n) {
                        val event = makeEvent(if (n > 1) "${base}_$i" else base)
                        tracked += JSONObject(event).getString("event_id")
                        db.insertEvent(event)
                    }
                }
                "flush" -> flush()
                "go_offline" -> online = false
                "go_online" -> online = true
                "restart" -> db = EventDatabase(ctx, cap, dbName, maxBytes) // persistence survives (SQLite + prefs)
                "redeliver" -> { hadRedeliver = true; flush() }
                "advance_time_ms" -> {}
                else -> fail("[${f.id}] unknown pipeline op: ${s.op}")
            }
        }

        // The disk quota: what is left, and what it cost (read before `dropped_events_min` resets the counter).
        val left = db.loadAll().map { JSONObject(it).getString("event_id") }
        if (f.expect.liveBytesWithinQuota) {
            assertTrue("[${f.id}] live bytes within the quota (${db.liveBytesForTest()} of $maxBytes)", db.liveBytesForTest() <= maxBytes)
        }
        f.expect.minLiveBytesFraction?.let {
            assertTrue("[${f.id}] the quota is spent on events (live ${db.liveBytesForTest()} of $maxBytes)",
                db.liveBytesForTest() > it * maxBytes)
        }
        if (f.expect.survivorsAreNewest) {
            assertTrue("[${f.id}] nothing survived", left.isNotEmpty())
            assertEquals("[${f.id}] the survivors are the newest, in order", tracked.takeLast(left.size), left)
        }
        if (f.expect.droppedExact) {
            assertEquals("[${f.id}] each evicted event counted once", tracked.size - left.size, DroppedEventsCounter.peek())
        }
        f.expect.ingestedCount?.let { assertEquals("[${f.id}] ingested_count", it, ingested.size) }
        f.expect.droppedMin?.let {
            val dropped = DroppedEventsCounter.getAndReset()
            assertTrue("[${f.id}] dropped_events_min (got $dropped)", dropped >= it)
        }
        if (f.expect.noDup) {
            assertEquals("[${f.id}] no_duplicate_event_id", ingested.map { it.getString("event_id") }.toSet().size, ingested.size)
        }
        if (f.expect.monotonic) {
            val seqs = ingested.map { it.getJSONObject("context").getLong("client_seq") }
            assertEquals("[${f.id}] every ingested event carries a client_seq", ingested.size, seqs.size)
            // Assert the INGESTED (returned) order is ALREADY ascending by client_seq — do NOT sort.
            // This is what `ingested_order_key: client_seq` promises: the store returns events in
            // client_seq order, never wall-clock. A CL-6 intra-second reorder regression fails here.
            for (i in 1 until seqs.size) {
                assertTrue("[${f.id}] ingested client_seq strictly increasing IN RETURNED ORDER (index $i)", seqs[i] > seqs[i - 1])
            }
        }
        // A `redeliver` step MUST actually re-send unacked events (else idempotency is never exercised).
        if (hadRedeliver) {
            assertTrue("[${f.id}] redeliver must re-send unacked events (raw $rawSent vs ingested ${ingested.size})", rawSent > ingested.size)
        }

        db.clearAll()
        resetCounters()
    }

    /**
     * SPEC-428 STEP-9/§4.E — a pre-init event stamps its client_seq at track() time and carries it through
     * the drain, used VERBATIM: even though it's BUILT after a later post-configure event, its reserved
     * (earlier-stamped) seq stays lower → the configure-window inversion is fixed.
     */
    @Test
    fun preInitClientSeqCarry() {
        ClientSeqCounter.init(ctx)
        resetCounters()
        val id = DeviceIdentity("spec428-anon", null, null)
        val preInitSeq = ClientSeqCounter.next() // stamp before configure, in tracking order
        val post = EventSchema.buildEnvelope("post", null, id, "s", "1.0", true)
        val pre = EventSchema.buildEnvelope("pre", null, id, "s", "1.0", true, clientSeq = preInitSeq)
        assertEquals("carried seq used verbatim", preInitSeq, pre.getJSONObject("context").getLong("client_seq"))
        assertTrue("pre-init keeps its reserved LOWER seq despite being built after the post event",
            pre.getJSONObject("context").getLong("client_seq") < post.getJSONObject("context").getLong("client_seq"))
        resetCounters()
    }

    /** D6 — client_seq is persistence-backed: it continues from the persisted value across a (re-init'd) restart. */
    @Test
    fun clientSeqPersistsAcrossRestart() {
        ClientSeqCounter.init(ctx)
        resetCounters()
        val a = ClientSeqCounter.next()
        val b = ClientSeqCounter.next()
        assertEquals(a + 1, b)
        ClientSeqCounter.init(ctx) // simulate a cold restart — re-init resumes from the persisted CEILING
        val c = ClientSeqCounter.next()
        // Reserve-block (STEP-6): after restart the sequence resumes ABOVE the reserved ceiling — a GAP is
        // expected (never a reuse/reset), so c is strictly greater than b, not necessarily b+1.
        assertTrue("client_seq resumes monotonically across restart (gap OK, never reuse/reset)", c > b)
        resetCounters()
    }

    /** R14 / §6 — concurrent next() from many threads must NEVER hand out a duplicate client_seq. */
    @Test
    fun clientSeqConcurrentEmitUnique() {
        ClientSeqCounter.init(ctx)
        resetCounters()
        val perThread = 100
        val threadCount = 8
        val seqs = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val threads = (0 until threadCount).map { Thread { repeat(perThread) { seqs.add(ClientSeqCounter.next()) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("R14: concurrent next() must hand out no duplicate client_seq", threadCount * perThread, seqs.size)
        resetCounters()
    }

    private fun loadEventFixtures(): List<Fixture> {
        val eventsDir = fixturesRoot()?.let { File(it, "events") }
            ?: error("Could not locate packages/sdk-shared-fixtures/events")
        val files = eventsDir.listFiles { file -> file.name.endsWith(".fixture.json") } ?: emptyArray()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val j = JSONObject(file.readText())
            if (j.getString("category") != "events") return@mapNotNull null
            val plats = j.getJSONArray("platforms")
            val hasAndroid = (0 until plats.length()).any { plats.getString(it) == "android" }
            if (!hasAndroid) return@mapNotNull null
            val p = j.getJSONObject("pipeline")
            val cfg = p.optJSONObject("config")
            val stepsArr = p.getJSONArray("steps")
            val steps = (0 until stepsArr.length()).map {
                val s = stepsArr.getJSONObject(it)
                Step(
                    op = s.getString("op"),
                    name = if (s.has("name")) s.getString("name") else null,
                    count = if (s.has("count")) s.getInt("count") else null,
                    ms = if (s.has("ms")) s.getInt("ms") else null,
                )
            }
            val ex = p.getJSONObject("expect")
            Fixture(
                id = j.getString("id"),
                cap = if (cfg != null && cfg.has("max_events")) cfg.getInt("max_events") else null,
                maxBytes = if (cfg != null && cfg.has("max_bytes")) cfg.getInt("max_bytes") else null,
                steps = steps,
                expect = Expect(
                    ingestedCount = if (ex.has("ingested_count")) ex.getInt("ingested_count") else null,
                    droppedMin = if (ex.has("dropped_events_min")) ex.getInt("dropped_events_min") else null,
                    noDup = ex.optBoolean("no_duplicate_event_id", false),
                    monotonic = ex.optBoolean("monotonic_client_seq", false),
                    liveBytesWithinQuota = ex.optBoolean("live_bytes_within_quota", false),
                    minLiveBytesFraction = if (ex.has("min_live_bytes_fraction")) ex.getDouble("min_live_bytes_fraction") else null,
                    survivorsAreNewest = ex.optBoolean("survivors_are_newest", false),
                    droppedExact = ex.optBoolean("dropped_exact", false),
                ),
            )
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
