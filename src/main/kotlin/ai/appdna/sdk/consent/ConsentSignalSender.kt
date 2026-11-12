package ai.appdna.sdk.consent

import ai.appdna.sdk.Log
import ai.appdna.sdk.consent.ConsentSyncTable.Effect
import ai.appdna.sdk.consent.ConsentSyncTable.Event
import ai.appdna.sdk.consent.ConsentSyncTable.Slot
import ai.appdna.sdk.network.ConsentPostOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The SDK analytics-consent signal: the one request that tells AppDNA a device declined (or granted)
 * analytics, so the server can skip its own analytics-only side effects for that user. It bypasses the event
 * pipeline on purpose — a declined user's events never leave the device, so nothing else would carry the decision.
 *
 * Every transition runs on ONE serial executor (`limitedParallelism(1)` over the installed dispatcher) through the
 * pure [ConsentSyncTable]. The persisted half of each new state is written before its effects run, so a SEND's
 * "ack := unknown" is on disk before the request leaves.
 *
 * **The epoch fence.** A sender belongs to one configure; `shutdown()` and the test reset end its epoch ([end]).
 * Every transition first checks the epoch: a stale completion, retry fire or evaluation writes nothing and sends
 * nothing, and a stale public call is handed to the current sender (or held as a row-0 call when there is none).
 */
internal class ConsentSignalSender(
    baseDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long,
    private val persistence: ConsentSignalPersistence,
    private val post: (String) -> ConsentPostOutcome,
    /** A public call that reached a stale sender is re-run on the current epoch. */
    private val handoff: (Event) -> Unit,
    private val random: Random = Random.Default,
) {
    val epoch: Long = EPOCH.get()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = baseDispatcher.limitedParallelism(1)
    /** Transitions and timers: cancelled when the epoch ends. */
    private val scope = CoroutineScope(SupervisorJob() + serial)
    /** Requests: never cancelled, so an abandoned request's completion still reaches the fence. */
    private val requestScope = CoroutineScope(SupervisorJob() + serial)

    private var state: ConsentSyncTable.State = persistence.load()
    private var timerJob: Job? = null

    private val inFlightCount = AtomicInteger(0)
    private val completionsPending = AtomicInteger(0)
    private val deadline = AtomicLong(NO_DEADLINE)

    fun isCurrent(): Boolean = EPOCH.get() == epoch

    // ── Public triggers (rows 1, 2, 3) ───────────────────────────────────────────────────────────────

    fun setConsent(effective: ConsentSyncTable.Triple) = submit(Event.SetConsent(effective, nowMs()))
    fun userChanged(effective: ConsentSyncTable.Triple) = submit(Event.UserChanged(effective, nowMs()))
    fun configure(effective: ConsentSyncTable.Triple, rowZeroCall: Boolean) =
        submit(Event.Configure(effective, rowZeroCall, nowMs()))

    private fun submit(event: Event) {
        scope.launch { apply(event) }
    }

    /** Row 11: end this sender's epoch. Its timer is cancelled; an in-flight request is abandoned to the fence. */
    fun end() {
        EPOCH.incrementAndGet()
        scope.cancel()
        deadline.set(NO_DEADLINE)
    }

    // ── The serial executor ──────────────────────────────────────────────────────────────────────────

    private fun apply(event: Event) {
        if (!isCurrent()) {
            STALE_ARRIVALS.incrementAndGet()
            if (event is Event.SetConsent || event is Event.UserChanged) handoff(event)
            return
        }
        val result = ConsentSyncTable.next(state, event)
        // The test hold between a completion's check (the table has decided, from the state as it is now) and its
        // act (the state written, the effects run).
        if (event is Event.Success || event is Event.Transient || event is Event.Drop) ConsentTestLatch.pass("check_act")
        state = result.state
        persistence.save(state)
        for (fx in result.effects) run(fx)
    }

    private fun run(fx: Effect) {
        when (fx) {
            is Effect.Send -> send(fx.slot)
            is Effect.CancelTimer -> {
                timerJob?.cancel()
                timerJob = null
                deadline.set(NO_DEADLINE)
            }
            is Effect.ArmTimer -> {
                val delayMs = retryDelayMs(fx.retry, fx.retryAfterSeconds)
                val due = nowMs() + delayMs
                deadline.set(due)
                val g = fx.generation
                timerJob = scope.launch {
                    try {
                        delay(delayMs)
                    } finally {
                        // Cleared when the delay resumes or is cancelled, so a stale deadline never holds a test's
                        // advance loop — and only if no newer timer has set its own since.
                        deadline.compareAndSet(due, NO_DEADLINE)
                    }
                    apply(Event.RetryFire(g))
                }
            }
        }
    }

    private fun send(slot: Slot) {
        inFlightCount.incrementAndGet()
        val body = PrefsConsentSignalPersistence.body(slot)
        requestScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val o = try { post(body) } catch (t: Throwable) { ConsentPostOutcome.Transient(null, null) }
                completionsPending.incrementAndGet()
                inFlightCount.decrementAndGet()
                ConsentTestLatch.pass("io_return")
                o
            }
            completionsPending.decrementAndGet()
            apply(
                when (outcome) {
                    ConsentPostOutcome.Success -> Event.Success(slot.generation)
                    is ConsentPostOutcome.Transient -> Event.Transient(slot.generation, outcome.retryAfterSeconds)
                    is ConsentPostOutcome.Drop -> Event.Drop(slot.generation)
                },
            )
        }
    }

    /** 1 s, 2 s, 4 s, each ±25%; a `Retry-After` replaces the backoff. */
    private fun retryDelayMs(retry: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) return retryAfterSeconds * 1000
        val base = 1000L shl (retry - 1)
        val jitter = (base / 4)
        return base - jitter + (random.nextDouble() * 2 * jitter).toLong()
    }

    // ── Test hooks (reached through AppDNA's internal hooks; never public API) ──────────────────────

    /** Waits until no request is on the wire, then returns the completions not yet run; -1 on timeout. */
    fun awaitIdle(timeoutMs: Long): Int {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (inFlightCount.get() > 0) {
            if (System.nanoTime() > end) return -1
            Thread.sleep(2)
        }
        return completionsPending.get()
    }

    fun nextDeadline(): Long? = deadline.get().takeIf { it != NO_DEADLINE }

    internal fun stateForTesting(): ConsentSyncTable.State = state

    companion object {
        private const val NO_DEADLINE = Long.MIN_VALUE
        /** Process-static: an ended sender's epoch is never current again. */
        private val EPOCH = AtomicLong(0)
        val STALE_ARRIVALS = AtomicInteger(0)

        const val PATH = "/api/v1/ingest/consent"
    }
}

/**
 * Test-only hold points (`io_return`, `check_act`, `configure_hold`): the next pass through the armed point blocks
 * for `holdMs` of real time, once. The reset clears it. Inert in production (nothing arms it).
 */
internal object ConsentTestLatch {
    @Volatile private var point: String? = null
    @Volatile private var holdMs: Long = 0
    @Volatile private var engaged = CountDownLatch(1)

    fun arm(point: String, holdMs: Long) {
        engaged = CountDownLatch(1)
        this.holdMs = holdMs
        this.point = point
    }

    fun clear() { point = null }

    fun pass(at: String) {
        if (point != at) return
        synchronized(this) {
            if (point != at) return
            point = null
        }
        engaged.countDown()
        try { Thread.sleep(holdMs) } catch (_: InterruptedException) { Log.debug("consent latch interrupted") }
    }

    fun awaitEngaged(timeoutMs: Long): Boolean = engaged.await(timeoutMs, TimeUnit.MILLISECONDS)
}
