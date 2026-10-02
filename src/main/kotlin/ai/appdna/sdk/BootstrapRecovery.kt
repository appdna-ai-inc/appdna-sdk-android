package ai.appdna.sdk

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Retries a failed bootstrap for the rest of the session — iOS `BootstrapRecovery`, same rules.
 *
 * A bootstrap that failed (offline launch, server unreachable or too slow) used to leave the SDK on cached
 * and bundled config for the whole session: no Firestore path, so no remote config fetch, no Firestore
 * listeners, no runtime lock, until the next `configure()`. Now the SDK tries again:
 *  - when a network comes back after none was available ([trigger] from the connectivity monitor);
 *  - when the app comes to the foreground ([trigger] from the process lifecycle);
 *  - after a backoff ([backoffMs]: 5 s, 10 s, 20 s, … capped at 5 min) — only while a network is available,
 *    so an offline device waits for one of the two triggers instead of using up its attempts.
 * At most [maxAttempts] attempts per configure (each one is a full bootstrap request with its own short
 * retries). [attempt] answers true when there is nothing more to do: the bootstrap was applied, or its
 * configure has ended (`shutdown()` / a later `configure()` — the epoch moved on). [stop] ends the loop.
 */
internal class BootstrapRecovery(
    private val isOnline: () -> Boolean,
    private val backoffMs: (attemptIndex: Int) -> Long = ::defaultBackoffMs,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var job: Job? = null
    /** Attempts made so far (test reader). */
    @Volatile var attempts: Int = 0
        private set

    /** Connectivity came back, or the app came to the foreground: try now (if the loop is waiting). */
    fun trigger() {
        triggers.trySend(Unit)
    }

    fun start(scope: CoroutineScope, attempt: suspend () -> Boolean) {
        job = scope.launch {
            while (attempts < maxAttempts) {
                // Wait for a trigger, or for the backoff. Offline neither helps: keep waiting, and let the
                // network coming back (a trigger) start the attempt — an offline device does not use up
                // its attempts.
                val triggered = withTimeoutOrNull(backoffMs(attempts)) { triggers.receive() } != null
                if (!isOnline()) continue
                attempts += 1
                Log.info("Retrying the bootstrap (attempt $attempts of $maxAttempts${if (triggered) ", triggered" else ""})")
                if (attempt()) return@launch
            }
            Log.warning("Bootstrap still failing after $maxAttempts retries — cached and bundled config until the next configure()")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        triggers.close()
        val obs = lifecycleObserver ?: return
        lifecycleObserver = null
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
     */
    fun observeForeground() {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    var armed = false
                    val obs = object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            if (armed) trigger()
                        }
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
                    armed = true
                    lifecycleObserver = obs
                    if (job == null) { // stopped before this ran
                        ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
                        lifecycleObserver = null
                    }
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

        /** 5 s, 10 s, 20 s, 40 s, … capped at 5 minutes. */
        fun defaultBackoffMs(attemptIndex: Int): Long =
            (5_000L shl attemptIndex.coerceIn(0, 6)).coerceAtMost(300_000L)
    }
}
