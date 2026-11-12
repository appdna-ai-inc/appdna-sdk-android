package ai.appdna.sdk.consent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The consent sender's real thread interleavings, driven through the real `AppDNA` singleton.
 *
 * The enumeration ([ConsentSyncTableEnumerationTest]) walks every short sequence against the pure table, and the shared
 * fixtures settle after every op, so neither ever lets two things happen at once. These cases do: a completion is held
 * at a test latch (`io_return`, after the request returned; `check_act`, after its completion was decided;
 * `configure_hold`, inside `configure` before the sender exists) for 2 s of real time, against the runner's 10 s
 * settle timeout, while another call lands; or the stub holds a request while the SDK's epoch ends. Each runs through
 * [ConsentFixtureRunner] with the timing ops it has for these tests only.
 *
 * Each case states the defect that turns it red. The red controls were run against those exact mutants of the SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentSenderTimingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val epoch = 946_684_800_000L
    private val anon = "anon-device-a"
    private val user = "user-one"

    private fun triple(analytics: Boolean, u: String = user) =
        JSONObject().put("anon_id", anon).put("user_id", u).put("analytics", analytics)

    private fun request(analytics: Boolean, ts: LongRange = 0L..60_000L, sincePrev: LongRange? = null): JSONObject {
        val body = triple(analytics).put("ts_ms", JSONObject().put("gte", epoch + ts.first).put("lte", epoch + ts.last))
        val r = JSONObject().put("method", "POST").put("path", ConsentSignalSender.PATH).put("body", body)
        sincePrev?.let { r.put("since_prev_ms", JSONObject().put("gte", it.first).put("lte", it.last)) }
        return r
    }

    private fun setup(ack: JSONObject?, vararg replies: JSONObject): JSONObject {
        val s = JSONObject().put("clock_epoch_ms", epoch).put("anon_id", anon).put("persisted_user_id", user)
            .put("request_replies", JSONArray(replies.toList()))
        ack?.let { s.put("ack_state", it) }
        return s
    }

    private fun reply(status: Int, delayMs: Long = 0) =
        JSONObject().put("status", status).also { if (delayMs > 0) it.put("delay_ms", delayMs) }

    private fun op(kind: String, vararg kv: Pair<String, Any>) = JSONObject().put("kind", kind).also { o -> kv.forEach { o.put(it.first, it.second) } }
    private val configure get() = op("configure")
    private fun setConsent(v: Boolean, settle: Any? = null) = op("set_consent", "analytics" to v).also { o -> settle?.let { o.put("settle", it) } }
    private fun arm(point: String) = op("arm_latch", "point" to point, "hold_ms" to 2000L)
    private val awaitLatch get() = op("await_latch")

    private fun run(name: String, setup: JSONObject, script: List<JSONObject>, expect: List<JSONObject>): ConsentFixtureRunner.Outcome =
        ConsentFixtureRunner(ctx).runCase(
            JSONObject().put("name", name).put("setup", setup).put("script", JSONArray(script))
                .put("expect", JSONObject().put("requests", JSONArray(expect))),
        )

    /**
     * Case 1. A completion held at `io_return` while a newer decision arrives: the newer one is kept and sent after
     * the held one lands, even though it equals the ack the device had before the first send.
     * Red (a superseded completion clears the newer slot instead of evaluating it): 1 request.
     */
    @Test
    fun heldCompletionKeepsTheNewerDecision() {
        run(
            "held_completion_at_io_return", setup(triple(true), reply(202)),
            listOf(configure, arm("io_return"), setConsent(false, "pump"), awaitLatch, setConsent(true), op("assert_ack", "ack" to triple(true))),
            listOf(request(false), request(true)),
        )
    }

    /**
     * The cross-epoch tie. A dead sender's `false` is held by the server for 2 s and applied last, after the new
     * epoch's `true`, with the injected clock never advanced. The later decision's `ts_ms` is strictly greater, so the
     * server's latest row stays `true`.
     * Red (`ts_ms` taken from the clock alone, no strictly increasing rule): equal `ts_ms`, and the model row ends `false`.
     */
    @Test
    fun crossEpochTieKeepsTheLaterDecision() {
        val out = run(
            "cross_epoch_tie", setup(triple(true), reply(202, delayMs = 2000), reply(202)),
            listOf(
                configure, setConsent(false, "pump"), op("await_recorded", "count" to 1), op("shutdown"),
                setConsent(true), configure, op("await_stale", "count" to 1),
            ),
            listOf(request(false, 0L..0L), request(true, 1L..1L)),
        )
        assertEquals(2, out.recorded.size)
        val ts = out.recorded.map { JSONObject(it.body).getLong("ts_ms") }
        assertTrue("the later decision must carry a strictly greater ts_ms: $ts", ts[1] > ts[0])
        assertEquals("the server's latest row must be the later decision", true, out.modelAnalytics)
        assertTrue("the dead false's completion never reached the fence", out.staleArrivals >= 1)
    }

    /**
     * The counter control. The settle must wait for a completion that is owed but not yet run (`completionsPending`),
     * or a relaunch discards the sender before the ack is written and the next configure re-sends.
     * Red (no `completionsPending`; `inFlight` decremented at the same site, before the latch): 2 requests.
     */
    @Test
    fun settleWaitsForAnOwedCompletion() {
        run(
            "counter_control", setup(null, reply(202)),
            listOf(configure, arm("io_return"), setConsent(true), op("relaunch"), configure),
            listOf(request(true)),
        )
    }

    /**
     * `configure_hold`. `configure` runs on a second thread and is held inside its monitor after it has read the
     * held decision and before the sender exists; `setConsent(true)` lands meanwhile. It must wait on the monitor and
     * then run as a row-1 call on the new sender, which clears the dropped triple and sends. `dropped` = E and the
     * ack unknown are built in-script by a 400 and a relaunch.
     * Red (`setConsent` not under the monitor): the held decision is written after `configure` read it, and never sent.
     */
    @Test
    fun setConsentDuringConfigureWaitsForTheSender() {
        run(
            "configure_hold", setup(triple(true, "user-two"), reply(400), reply(202)),
            listOf(
                configure, op("relaunch"), arm("configure_hold"), op("spawn", "ops" to JSONArray(listOf(configure))),
                awaitLatch, setConsent(true), op("join"),
            ),
            listOf(request(true), request(true)),
        )
    }

    /**
     * Identical bodies. Generation 1 is held at `io_return`; generations 2 and 3 replace it (3 has generation 1's
     * decision). Every reply is 503. Generation 1's completion is superseded, so generation 3 is sent and retried on
     * its own: 5 requests, the retries at about 1 s, 2 s and 4 s.
     * Red (the completion matched to the slot by its decision, not its generation): generation 3 is never sent on its own.
     */
    @Test
    fun identicalBodiesAreToldApartByGeneration() {
        run(
            "identical_body", setup(triple(true), reply(503)),
            listOf(
                configure, arm("io_return"), setConsent(false, "pump"), awaitLatch,
                setConsent(true, "pump"), setConsent(false, "pump"), op("settle"), op("advance_clock_ms", "ms" to 30_000L),
            ),
            listOf(
                request(false, 0L..0L), request(false, 2L..2L),
                request(false, 2L..2L, 750L..1250L), request(false, 2L..2L, 1500L..2500L), request(false, 2L..2L, 3000L..5000L),
            ),
        )
    }

    /**
     * The barrier. A 503's completion is held at `check_act` (decided, not yet acted on) on the serial executor; a
     * `setConsent(true)` from another thread lands meanwhile. It must queue behind the completion on the executor, so
     * it cancels the retry the completion arms and is sent next.
     * Red (the replacement runs on the calling thread, not posted to the executor): the completion's act overwrites
     * the replacement and its retry re-sends `false`.
     */
    @Test
    fun aReplacementQueuesBehindAHeldCompletion() {
        run(
            "check_act_barrier", setup(triple(true), reply(503), reply(202)),
            listOf(
                configure, arm("check_act"), op("spawn", "ops" to JSONArray(listOf(awaitLatch, setConsent(true)))),
                setConsent(false), op("join"), op("advance_clock_ms", "ms" to 2000L),
                op("assert_ack", "ack" to triple(true), "dropped" to JSONObject.NULL),
            ),
            listOf(request(false), request(true)),
        )
    }

    /**
     * The reused connection. After the warm-up (the bootstrap answered and read, so its connection is pooled), the
     * consent POST goes out on that pooled connection and the server closes it without a reply. The SDK must not
     * re-send it behind its own retry policy: exactly one request, on a reused connection.
     * Red (OkHttp's default `retryOnConnectionFailure(true)` on the consent client): the POST is re-sent on a new
     * connection, 2 requests.
     */
    @Test
    fun aFailedPostOnAReusedConnectionIsNotResent() {
        val out = run(
            "reused_connection", setup(triple(true), JSONObject().put("network_failure", true)),
            listOf(configure, op("await_ready"), setConsent(false)),
            listOf(request(false)),
        )
        assertTrue("the consent POST must arrive on a reused connection: ${out.recorded}", out.recorded.single().connectionIndex > 0)
    }
}
