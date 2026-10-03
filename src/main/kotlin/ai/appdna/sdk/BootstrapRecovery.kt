package ai.appdna.sdk

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Retries a failed bootstrap for the rest of the session — iOS `BootstrapRecovery`, same rules.
 *
 * A bootstrap that failed (offline launch, server unreachable or too slow) used to leave the SDK on cached
 * and bundled config for the whole session: no Firestore path, so no remote config fetch, no Firestore
 * listeners, no runtime lock, until the next `configure()`. Now the SDK tries again:
 *  - when a network comes back after none was available ([trigger] from the connectivity monitor);
 *  - when the app comes to the foreground ([trigger] from the process lifecycle);
 *  - after a backoff ([backoffMs]: 5 s, 10 s, 20 s, … capped at 5 min, each ±25 % jitter) — only while a
 *    network is available, so an offline device waits for one of the two triggers instead of using up its
 *    attempts.
 * A trigger starts its attempt after a short random delay (up to [triggerDelayMaxMs]), so a fleet that
 * regains the network together does not reach the server in the same instant.
 * At most [maxAttempts] attempts per configure (each one is a full bootstrap request with its own short
 * retries). [attempt] answers an [Outcome]: [Outcome.Done] when there is nothing more to do (the bootstrap
 * was applied, or its configure has ended — `shutdown()` / a later `configure()` moved the epoch on),
 * [Outcome.Retry] after a failure worth retrying, [Outcome.RetryAfter] when the server rate-limited the
 * request (no attempt — not even a triggered one — before then), [Outcome.Stop] when the server refused
 * the key (401 / 403: retrying cannot help). [stop] ends the loop; a [start] after [stop] does nothing.
 * When the loop ends — for any reason — it removes its foreground observer and runs [onEnded].
 *
 * The configure-time bootstrap's own failure is the loop's starting point: [start]'s `initial` outcome, so a 429
 * at configure holds the FIRST retry back for its `Retry-After` too (before, only a rate-limited retry did).
 * Triggers coalesce: a trigger that arrives while an attempt is in flight (or in its trigger delay) is spent by
 * that attempt — it does not start the next one 0–1 s later, so a failed attempt is always followed by its
 * backoff (or a later trigger). iOS, same rules.
 */
internal class BootstrapRecovery(
    private val isOnline: () -> Boolean,
    private val backoffMs: (attemptIndex: Int) -> Long = ::defaultBackoffMs,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val random: () -> Double = { Random.nextDouble() },
    private val triggerDelayMaxMs: Long = DEFAULT_TRIGGER_DELAY_MAX_MS,
) {
    /** What one attempt came to. */
    sealed class Outcome {
        object Done : Outcome()
        object Retry : Outcome()
        object Stop : Outcome()
        data class RetryAfter(val ms: Long) : Outcome()
    }

    private val lock = Any()
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var stopped = false
    @Volatile private var ended = false
    /** Attempts made so far (test reader). */
    @Volatile var attempts: Int = 0
        private set
    /** Runs once when the loop ends (stopped, applied, refused, or out of attempts). */
    @Volatile var onEnded: (() -> Unit)? = null
    /** Whether a loop was launched (test reader). */
    internal val wasLaunchedForTest: Boolean get() = synchronized(lock) { job != null }
    /** Whether the foreground observer is registered now (test reader). */
    internal val isObservingForeground: Boolean get() = lifecycleObserver != null
    /** Whether the loop is waiting for a trigger or its backoff now (test reader). */
    @Volatile internal var isWaitingForTest: Boolean = false
        private set

    /** Connectivity came back, or the app came to the foreground: try now (if the loop is waiting). */
    fun trigger() {
        triggers.trySend(Unit)
    }

    /**
     * Starts the loop. [initial] is the outcome of the configure-time bootstrap that failed (null: none known): an
     * [Outcome.RetryAfter] holds the first attempt back, like a rate-limited retry's.
     */
    fun start(scope: CoroutineScope, initial: Outcome? = null, attempt: suspend () -> Outcome) {
        val firstNotBefore = (initial as? Outcome.RetryAfter)?.let { now() + it.ms } ?: 0L
        synchronized(lock) {
            if (stopped || job != null) return
            job = scope.launch {
                try {
                    run(attempt, firstNotBefore)
                } finally {
                    end()
                }
            }
        }
    }

    private suspend fun run(attempt: suspend () -> Outcome, initialNotBefore: Long) {
        var notBefore = initialNotBefore
        while (attempts < maxAttempts) {
            // Wait for a trigger, or for the backoff. Offline neither helps: keep waiting, and let the
            // network coming back (a trigger) start the attempt — an offline device does not use up its
            // attempts. Rate limited, a trigger does not shorten the wait the server asked for.
            val deadline = maxOf(now() + jittered(backoffMs(attempts), random()), notBefore)
            var triggered = false
            while (true) {
                val remaining = deadline - now()
                if (remaining <= 0) break
                isWaitingForTest = true
                val got = (try {
                    withTimeoutOrNull(remaining) { triggers.receiveCatching() }
                } finally {
                    isWaitingForTest = false
                }) ?: break
                if (got.isClosed) return
                if (now() < notBefore) continue
                triggered = true
                break
            }
            if (triggered && triggerDelayMaxMs > 0) delay((triggerDelayMaxMs * random()).toLong())
            if (!isOnline()) continue
            notBefore = 0L
            attempts += 1
            Log.info("Retrying the bootstrap (attempt $attempts of $maxAttempts${if (triggered) ", triggered" else ""})")
            val o = attempt()
            // Coalesce: a trigger that came during this attempt (or its trigger delay) was answered by it.
            while (triggers.tryReceive().isSuccess) { /* drop */ }
            when (o) {
                Outcome.Done -> return
                Outcome.Stop -> {
                    Log.warning("Bootstrap retries stopped — the server refused the API key; cached and bundled config until the next configure()")
                    return
                }
                Outcome.Retry -> Unit
                is Outcome.RetryAfter -> notBefore = now() + o.ms
            }
        }
        Log.warning("Bootstrap still failing after $maxAttempts retries — cached and bundled config until the next configure()")
    }

    fun stop() {
        val j = synchronized(lock) {
            stopped = true
            job.also { job = null }
        }
        j?.cancel()
        end()
    }

    /** The loop is over: no more triggers, no foreground observer, and [onEnded] once. */
    private fun end() {
        val first = synchronized(lock) { (!ended).also { ended = true } }
        triggers.close()
        removeForegroundObserver()
        if (first) try { onEnded?.invoke() } catch (e: Throwable) { Log.warning("Bootstrap recovery end hook threw: ${e.message}") }
    }

    private fun removeForegroundObserver() {
        val obs = synchronized(lock) { lifecycleObserver.also { lifecycleObserver = null } } ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    @Volatile private var lifecycleObserver: DefaultLifecycleObserver? = null

    /**
     * Trigger on every return to the foreground. `addObserver` replays the current state — an app that is
     * already in the foreground gets `onStart` at once — and that replay is not a return: it is ignored.
     * A loop that has already ended (stopped, or finished before this ran on the main thread) gets no
     * observer.
     */
    fun observeForeground() {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (ended) return@post
                    var armed = false
                    val obs = object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            if (armed) trigger()
                        }
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
                    armed = true
                    val keep = synchronized(lock) { if (ended) false else { lifecycleObserver = obs; true } }
                    if (!keep) ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) // ended meanwhile
                } catch (e: Throwable) {
                    Log.warning("Could not observe the foreground for bootstrap retries: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("Foreground observer scheduling failed: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        /** The jitter applied to every backoff wait: ±25 %. */
        const val JITTER_PCT = 0.25
        /** The longest random delay before a triggered attempt. */
        const val DEFAULT_TRIGGER_DELAY_MAX_MS = 1_000L

        /** 5 s, 10 s, 20 s, 40 s, … capped at 5 minutes (before jitter). */
        fun defaultBackoffMs(attemptIndex: Int): Long =
            (5_000L shl attemptIndex.coerceIn(0, 6)).coerceAtMost(300_000L)

        /** [baseMs] with ±[JITTER_PCT] jitter; [unit] is a random number in [0, 1). */
        fun jittered(baseMs: Long, unit: Double): Long =
            (baseMs * (1 - JITTER_PCT + 2 * JITTER_PCT * unit)).toLong().coerceAtLeast(0L)

        /**
         * The outcome of a failed bootstrap request. [status] is the HTTP status, null when no HTTP answer
         * came (network error, timeout, unreadable answer). 401 / 403 end the loop; a 429 with a
         * `Retry-After` ([retryAfterSeconds], already parsed and capped by `ApiClient.parseRetryAfter`) is
         * honoured; everything else is retried on the backoff schedule. iOS `BootstrapRecovery.outcome`.
         */
        fun outcomeForFailure(status: Int?, retryAfterSeconds: Long?): Outcome = when (status) {
            401, 403 -> Outcome.Stop
            429 -> retryAfterSeconds?.let { Outcome.RetryAfter(it * 1000L) } ?: Outcome.Retry
            else -> Outcome.Retry
        }

        private fun now(): Long = System.nanoTime() / 1_000_000L
    }
}
