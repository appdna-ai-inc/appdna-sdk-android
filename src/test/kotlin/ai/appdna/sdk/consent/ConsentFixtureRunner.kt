package ai.appdna.sdk.consent

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAOptions
import ai.appdna.sdk.Environment
import ai.appdna.sdk.network.BASE_URL_OVERRIDE_META_KEY
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives the shared consent fixtures (`event_emission/consent_*`, `action.kind == "script"`) through the REAL
 * `AppDNA` singleton: install the clock seam, reset, seed the stores, then run each script op and settle.
 *
 * Settling: `runCurrent()` on the injected scheduler (never `advanceUntilIdle`, which would run a
 * retry), then the sender's idle hook (nothing on the wire), repeated until nothing is due, nothing is in flight and
 * no completion is pending. `advance_clock_ms` advances deadline by deadline, settling at each, so a chained retry
 * exists. The stub server records each consent POST with `at_ms` on the injected clock and answers it from the
 * case's `request_replies` (the last repeats; none means 202 `{}`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal open class ConsentFixtureRunner(private val ctx: Context) {

    // ── The SDK under test. The native runner calls AppDNA directly; the wrapper runners (RN, Flutter plugin)
    // override these to go through their bridged module and reach the internal hooks by reflection. ──────────
    open fun installClock(dispatcher: kotlinx.coroutines.CoroutineDispatcher?, now: (() -> Long)?) =
        AppDNA.installConsentClockForTesting(dispatcher, now)
    open fun resetSdk() = AppDNA.resetForTesting()
    open fun seed(setupJson: String) = AppDNA.seedConsentFixtureForTesting(ctx, setupJson)
    open fun awaitIdle(timeoutMs: Long): Int = AppDNA.awaitConsentSenderIdleForTesting(timeoutMs)
    open fun nextDeadline(): Long? = AppDNA.nextConsentDeadlineForTesting()
    open fun configure(requireConsent: Boolean) = AppDNA.configure(
        ctx, "adn_test_consent_fixture", Environment.SANDBOX, AppDNAOptions(requireConsent = requireConsent),
    )
    open fun setConsent(analytics: Boolean) = AppDNA.setConsent(analytics)
    open fun identify(userId: String) = AppDNA.identify(userId)
    open fun reset() = AppDNA.reset()
    open fun shutdown() = AppDNA.shutdown()

    private val settleTimeoutMs = 10_000L

    fun runAll(fixtureName: String, action: JSONObject) {
        val cases = action.getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            try {
                runCase(c)
            } catch (e: AssertionError) {
                failures += "[$fixtureName / ${c.getString("name")}] ${e.message}"
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    /** What a case leaves behind for an SDK-target test to assert beyond `expect.requests`. */
    class Outcome {
        var recorded: List<Recorded> = emptyList()
        var modelAnalytics: Boolean? = null
        var staleArrivals = 0
    }

    /** Runs one case (built in code by an SDK-target test, or read from a fixture) and returns what it left. */
    fun runCase(case: JSONObject): Outcome {
        val outcome = Outcome()
        val setup = case.getJSONObject("setup")
        val epoch = setup.getLong("clock_epoch_ms")
        val script = case.getJSONArray("script")
        validateScript(script)

        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val server = ConsentStubServer(setup.optJSONArray("request_replies")) { scheduler.currentTime }
        try {
            // install, reset, seed, configure (the fixture's order)
            installClock(dispatcher) { epoch + scheduler.currentTime }
            resetSdk()
            pointAt(server.baseUrl)
            wipeStores()
            seed(setup.toString())

            val spawned = mutableListOf<Thread>()
            val spawnErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            for (i in 0 until script.length()) {
                val op = script.getJSONObject(i)
                when (val kind = op.getString("kind")) {
                    "configure" -> configure(op.optBoolean("require_consent", false))
                    "set_consent" -> setConsent(op.getBoolean("analytics"))
                    "identify" -> identify(op.getString("userId"))
                    "reset" -> reset()
                    "shutdown" -> shutdown()
                    "relaunch" -> resetSdk()
                    "advance_clock_ms" -> { advance(scheduler, epoch, op.getLong("ms")); continue }
                    // ── SDK-target timing ops (built in code by `ConsentSenderTimingTest`, never in a fixture file) ──
                    "arm_latch" -> { AppDNA.setConsentLatchForTesting(op.getString("point"), op.getLong("hold_ms")); continue }
                    "await_latch" -> {
                        assertTrue("the latch never engaged at op $i", AppDNA.awaitConsentLatchEngagedForTesting(settleTimeoutMs))
                        continue
                    }
                    "spawn" -> {
                        // The ops run in order on another thread, without settling, while this thread goes on.
                        val ops = op.getJSONArray("ops")
                        spawned += thread(name = "consent-timing-spawn") {
                            try {
                                for (j in 0 until ops.length()) runOffThread(ops.getJSONObject(j))
                            } catch (t: Throwable) { spawnErrors += t }
                        }
                        continue
                    }
                    "join" -> {
                        spawned.forEach { it.join(settleTimeoutMs) }
                        assertTrue("a spawned thread did not finish at op $i", spawned.none { it.isAlive })
                        spawnErrors.firstOrNull()?.let { throw AssertionError("spawned: ${it.message}", it) }
                    }
                    "settle" -> Unit
                    "await_recorded" -> {
                        val n = op.optInt("count", 1)
                        waitFor("the stub never received $n request(s) at op $i", scheduler) { server.recorded().size >= n }
                        continue
                    }
                    "await_stale" -> {
                        val n = op.optInt("count", 1)
                        waitFor("$n stale arrival(s) never reached the fence at op $i", scheduler) {
                            AppDNA.staleConsentArrivalsForTesting() >= n
                        }
                        outcome.staleArrivals = AppDNA.staleConsentArrivalsForTesting()
                    }
                    "await_ready" -> {
                        // The warm-up: the SDK is ready (its bootstrap answered and read), and no request is open.
                        val ready = java.util.concurrent.CountDownLatch(1)
                        AppDNA.onReady { ready.countDown() }
                        waitFor("the SDK never became ready at op $i", scheduler) { ready.count == 0L }
                        waitFor("the bootstrap was not served at op $i", scheduler) { server.bootstrapsServed() >= 1 && server.openRequests() == 0 }
                    }
                    "assert_ack" -> {
                        val raw = ai.appdna.sdk.storage.LocalStorage(ctx).getString(PrefsConsentSignalPersistence.ACK_KEY) ?: "{}"
                        val rec = JSONObject(raw)
                        val want = JSONObject().put("ack", op.get("ack"))
                        assertEquals("op $i: ack", PrefsConsentSignalPersistence.ackFrom(want), PrefsConsentSignalPersistence.ackFrom(rec))
                        if (op.has("dropped")) {
                            val got = rec.optJSONObject("dropped")?.let { PrefsConsentSignalPersistence.tripleFrom(it) }
                            val exp = op.optJSONObject("dropped")?.let { PrefsConsentSignalPersistence.tripleFrom(it) }
                            assertEquals("op $i: dropped", exp, got)
                        }
                        continue
                    }
                    else -> throw AssertionError("NO DRIVER for script op '$kind' on android")
                }
                // `settle: "pump"`: run what is due now without waiting for the wire, so a request can be held in flight
                // while the next op runs.
                if (op.optString("settle") == "pump") { pump(scheduler); continue }
                // `settle: false`: the next op follows at once, on the same tick (a call made before configure finished).
                if (op.optBoolean("settle", true)) settle(scheduler)
            }
            outcome.recorded = server.recorded()
            outcome.modelAnalytics = server.modelAnalytics()
            assertRequests(case.getJSONObject("expect").getJSONArray("requests"), server.recorded(), epoch)
        } finally {
            resetSdk()
            installClock(null, null)
            unpoint()
            server.close()
        }
        return outcome
    }

    /** One op on a spawned thread: an SDK call, or a wait for the latch. Never settles. */
    private fun runOffThread(op: JSONObject) {
        when (val k = op.getString("kind")) {
            "await_latch" -> assertTrue("spawned: the latch never engaged", AppDNA.awaitConsentLatchEngagedForTesting(settleTimeoutMs))
            "configure" -> configure(op.optBoolean("require_consent", false))
            "set_consent" -> setConsent(op.getBoolean("analytics"))
            "identify" -> identify(op.getString("userId"))
            "reset" -> reset()
            "shutdown" -> shutdown()
            else -> throw AssertionError("spawned: NO DRIVER for '$k' on android")
        }
    }

    /** Runs what is due now (the main looper, then the injected scheduler) without waiting for the wire. */
    private fun pump(scheduler: TestCoroutineScheduler) {
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.runCurrent()
    }

    private fun waitFor(what: String, scheduler: TestCoroutineScheduler, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleTimeoutMs)
        while (!cond()) {
            if (System.nanoTime() > end) throw AssertionError(what)
            pump(scheduler)
            Thread.sleep(2)
        }
    }

    /** A configure needs a relaunch, reinstall or shutdown since the previous one; only set_consent may run unconfigured. */
    private fun validateScript(script: JSONArray) {
        var configured = false
        for (i in 0 until script.length()) {
            when (val k = script.getJSONObject(i).getString("kind")) {
                "configure" -> { assertTrue("configure while configured at op $i", !configured); configured = true }
                "relaunch", "reinstall", "shutdown" -> {
                    if (k == "shutdown") assertTrue("shutdown while unconfigured at op $i", configured)
                    configured = false
                }
                "set_consent", "advance_clock_ms", "arm_latch", "await_latch", "spawn", "join", "settle",
                "await_recorded", "await_stale", "await_ready", "assert_ack" -> Unit
                else -> assertTrue("SDK call '$k' while unconfigured at op $i", configured)
            }
        }
    }

    private fun settle(scheduler: TestCoroutineScheduler) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleTimeoutMs)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runCurrent()
            val pending = awaitIdle(settleTimeoutMs)
            if (pending < 0) throw AssertionError("settle timed out: a consent request never completed")
            if (pending == 0) {
                scheduler.runCurrent()
                if (awaitIdle(settleTimeoutMs) == 0) return
            }
            if (System.nanoTime() > end) throw AssertionError("settle timed out with $pending completions pending")
            Thread.sleep(2)
        }
    }

    private fun advance(scheduler: TestCoroutineScheduler, epoch: Long, ms: Long) {
        val target = scheduler.currentTime + ms
        while (true) {
            val d = nextDeadline() ?: break
            val due = d - epoch
            if (due > target) break
            if (due > scheduler.currentTime) scheduler.advanceTimeBy(due - scheduler.currentTime)
            scheduler.runCurrent()
            settle(scheduler)
        }
        if (target > scheduler.currentTime) scheduler.advanceTimeBy(target - scheduler.currentTime)
        scheduler.runCurrent()
        settle(scheduler)
    }

    private fun pointAt(baseUrl: String) {
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.flags = pkg.applicationInfo!!.flags or ApplicationInfo.FLAG_DEBUGGABLE
        pkg.applicationInfo!!.metaData = Bundle().apply { putString(BASE_URL_OVERRIDE_META_KEY, baseUrl) }
    }

    private fun unpoint() {
        val pkg = shadowOf(ctx.packageManager).getInternalMutablePackageInfo(ctx.packageName)
        pkg.applicationInfo!!.metaData = null
    }

    /** Each case is a fresh device: no decision, no identity, no consent state. */
    private fun wipeStores() {
        for (name in listOf("ai.appdna.sdk", "ai.appdna.sdk.secure", "ai.appdna.sdk.consent")) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun assertRequests(expected: JSONArray, actual: List<Recorded>, epoch: Long) {
        val got = actual.joinToString("\n    ") { "at ${it.atMs}: ${it.body}" }
        assertEquals("request count; recorded:\n    $got", expected.length(), actual.size)
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val a = actual[i]
            val where = "requests[$i] (recorded:\n    $got)"
            assertEquals("$where method", e.getString("method"), "POST")
            assertEquals("$where path", e.getString("path"), "/api/v1/ingest/consent")
            val eb = e.getJSONObject("body")
            val ab = JSONObject(a.body)
            assertEquals("$where keys", eb.keys().asSequence().toSortedSet(), ab.keys().asSequence().toSortedSet())
            for (k in listOf("anon_id", "user_id", "analytics")) {
                assertEquals("$where body.$k", eb.opt(k), ab.opt(k))
            }
            inRange("$where body.ts_ms", ab.getLong("ts_ms"), eb.get("ts_ms"))
            e.optJSONObject("at_ms")?.let { inRange("$where at_ms", a.atMs, it) }
            e.optJSONObject("since_prev_ms")?.let { inRange("$where since_prev_ms", a.atMs - actual[i - 1].atMs, it) }
        }
    }

    private fun inRange(what: String, v: Long, m: Any) {
        if (m is Number) { assertEquals(what, m.toLong(), v); return }
        val o = m as JSONObject
        if (o.has("gte")) assertTrue("$what = $v < ${o.getLong("gte")}", v >= o.getLong("gte"))
        if (o.has("lte")) assertTrue("$what = $v > ${o.getLong("lte")}", v <= o.getLong("lte"))
    }

    /** One consent POST: when (injected clock), its body, and its index on its connection (0 = the first). */
    data class Recorded(val atMs: Long, val body: String, val connectionIndex: Int = 0)

    /**
     * Loopback stub. Consent POSTs are recorded and answered from the case's replies; bootstrap gets a valid answer
     * (so recovery never starts); everything else 202 `{}`. A `network_failure` reply reads the request, then closes.
     */
    private class ConsentStubServer(private val replies: JSONArray?, private val clock: () -> Long) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        private val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
        @Volatile private var closed = false

        init {
            thread(isDaemon = true, name = "consent-stub-accept") {
                while (!closed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true, name = "consent-stub-conn") { serve(s) }
                }
            }
        }

        fun recorded(): List<Recorded> = synchronized(recorded) { recorded.toList() }

        /**
         * A one-row model of the server's `sdk_consent_signals`: each 2xx request is applied when it is answered, by
         * `ts_ms` with `>=` (a later-answered request with an equal `ts_ms` wins), as the server does.
         */
        private var modelRow: Pair<Long, Boolean>? = null
        fun modelAnalytics(): Boolean? = synchronized(recorded) { modelRow?.second }
        private val bootstraps = java.util.concurrent.atomic.AtomicInteger(0)
        private val open = java.util.concurrent.atomic.AtomicInteger(0)
        fun bootstrapsServed() = bootstraps.get()
        fun openRequests() = open.get()

        private fun reply(index: Int): JSONObject? {
            val r = replies ?: return null
            if (r.length() == 0) return null
            return r.getJSONObject(minOf(index, r.length() - 1))
        }

        private fun serve(s: Socket) {
            try {
                val input = BufferedInputStream(s.getInputStream())
                var onConnection = 0
                while (!closed) {
                    val line = readLine(input) ?: return
                    val index = onConnection++
                    if (line.isEmpty()) continue
                    var length = 0
                    while (true) {
                        val h = readLine(input) ?: return
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0 && h.substring(0, i).trim().equals("content-length", true)) length = h.substring(i + 1).trim().toInt()
                    }
                    open.incrementAndGet()
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) return; read += n }
                    val parts = line.split(" ")
                    val path = parts.getOrElse(1) { "" }
                    var status = 202
                    var body = "{}"
                    val headers = mutableMapOf<String, String>()
                    if (parts[0] == "POST" && path == "/api/v1/ingest/consent") {
                        val idx: Int
                        val text = String(bytes, Charsets.UTF_8)
                        synchronized(recorded) { idx = recorded.size; recorded += Recorded(clock(), text, index) }
                        val r = reply(idx)
                        if (r != null && r.optBoolean("network_failure", false)) { open.decrementAndGet(); return }
                        if (r != null) {
                            status = r.getInt("status")
                            r.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
                            // A per-request delay: the request is held for `delay_ms` of real time before it is applied
                            // and answered.
                            r.optLong("delay_ms", 0).takeIf { it > 0 }?.let { Thread.sleep(it) }
                        }
                        if (status in 200..299) {
                            val b = JSONObject(text)
                            synchronized(recorded) {
                                val row = modelRow
                                if (row == null || b.getLong("ts_ms") >= row.first) modelRow = b.getLong("ts_ms") to b.getBoolean("analytics")
                            }
                        }
                    } else if (path.startsWith("/api/v1/sdk/bootstrap")) {
                        bootstraps.incrementAndGet()
                        status = 200
                        body = JSONObject().put("orgId", "org_consent").put("appId", "app_consent")
                            .put("firestorePath", "orgs/org_consent/apps/app_consent").put("settings", JSONObject()).toString()
                    }
                    val payload = body.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
                            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                            "Content-Length: ${payload.size}\r\n\r\n").toByteArray(Charsets.UTF_8),
                    )
                    out.write(payload)
                    out.flush()
                    open.decrementAndGet()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(c)
            }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
        }
    }
}
