package ai.appdna.sdk.core

// Instantiated + started in AppDNA.kt configure() (see the
// `/: SessionManager` block in that file).
//
// And expose the live session id to the event envelope by calling
// `eventTracker.setSessionIdProvider { sessionMgr.currentSessionId() }`
// (or whichever wiring `EventTracker` exposes — coordinator subagent will
// pick the cleanest hook).

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks app sessions based on foreground/background
 * lifecycle. Mirrors iOS `Core/Identity/SessionManager.swift`.
 *
 * Behavior:
 * - Cold launch with no recent activity (>30 min idle, or first install)
 *   creates a fresh session id and emits `session_start`.
 * - On a real background→foreground `ON_START`, if `lastActiveAt` is older
 *   than 30 min the previous session is ended (`session_end`) and a new one
 *   started; otherwise the same session resumes. Emits `app_open`. Foregrounds
 *   BEFORE the first background (the cold-launch initial `ON_START`, whether it
 *   replays synchronously at register time or arrives async) are skipped — iOS
 *   has no cold-launch `app_open` and `start()` already handled cold-start.
 *   See `hasBackgrounded`.
 * - On `ON_STOP` (background entry), persists `lastActiveAt` and emits
 *   `app_close`. Does NOT emit `session_end` on backgrounding — iOS
 *   parity (session ends only on the next cold start gap).
 *
 * Persists `lastActiveAt` and `sessionsThisInstall` via `SessionDataStore`
 * so they survive process death.
 *
 * Thread safety: lifecycle callbacks are dispatched on the main thread
 * by `ProcessLifecycleOwner`; mutable state goes through `Atomic*`
 * references so any other reader (event-envelope builder on the IO queue)
 * sees a consistent snapshot.
 */
// Internal because EventTracker (the parameter type) is internal — Kotlin
// would otherwise reject the public constructor for leaking an internal type.
internal class SessionManager(
    private val context: Context,
    private val eventTracker: EventTracker,
    private val sessionDataStore: SessionDataStore,
) {

    companion object {
        /** 30-minute idle threshold for session rotation. Matches iOS. */
        const val SESSION_TIMEOUT_MS: Long = 30L * 60L * 1000L

        internal const val KEY_LAST_ACTIVE_AT = "appdna_last_active_at"
        internal const val KEY_SESSIONS_THIS_INSTALL = "appdna_sessions_this_install"
    }

    private val sessionIdRef: AtomicReference<String> = AtomicReference(UUID.randomUUID().toString().lowercase())
    private val sessionsCountRef: AtomicLong = AtomicLong(0L)
    private var observer: LifecycleEventObserver? = null
    private var started = false
    // `app_open` + idle-rotation fire only on a foreground that FOLLOWS a background,
    // mirroring iOS (UIApplication.willEnterForeground does NOT fire on a direct cold launch). The
    // initial cold-launch foreground — whether ON_START replays synchronously at register time OR
    // arrives async when the first Activity starts (configure() runs in Application.onCreate, where
    // ProcessLifecycleOwner is only CREATED) — is skipped, because `start()` already handled the
    // cold-start session. Without this, that first handleForeground re-read the stale lastActiveAt
    // (start() never persists it) and rotated a SECOND time: phantom session_end + doubled
    // session_start/session_count for every returning user, plus a cold-launch app_open iOS omits.
    // Flips true on the first ON_STOP so real background→foreground returns process normally.
    private val hasBackgrounded = AtomicBoolean(false)

    /** Stable session id for the current session. Safe to call from any thread. */
    fun currentSessionId(): String = sessionIdRef.get()

    /** Total sessions started since install (including the current one). */
    fun sessionsThisInstall(): Long = sessionsCountRef.get()

    /**
     * Register the lifecycle observer and rotate the session if cold-start
     * is past the idle threshold. Safe to call multiple times — second
     * and later calls are no-ops.
     */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }

        // Hydrate sessions-this-install counter from persistence.
        val storedCount = (sessionDataStore.getSessionData(KEY_SESSIONS_THIS_INSTALL) as? Number)?.toLong() ?: 0L
        sessionsCountRef.set(storedCount)

        // Cold-start decision: if last active was >30 min ago (or never),
        // start a new session immediately. Otherwise we keep the freshly
        // generated UUID but it doesn't matter — `onStart` from the
        // lifecycle observer fires almost immediately after configure on
        // a foreground launch and re-evaluates anyway. Doing the check
        // here ensures the session id is correct for any pre-foreground
        // events (e.g. `sdk_initialized`).
        val now = System.currentTimeMillis()
        val lastActive = lastActiveAtMs()
        if (lastActive == null || now - lastActive >= SESSION_TIMEOUT_MS) {
            startNewSession()
        } else {
            Log.debug("SessionManager: resuming session (last active ${now - lastActive}ms ago)")
        }

        // Register lifecycle observer on the main thread — ProcessLifecycleOwner
        // requires registration on main.
        try {
            val owner: LifecycleOwner = ProcessLifecycleOwner.get()
            val obs = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> handleForeground()
                    Lifecycle.Event.ON_STOP -> handleBackground()
                    else -> Unit
                }
            }
            this.observer = obs
            // Lifecycle.addObserver must run on main; ProcessLifecycleOwner
            // dispatches its callbacks on main but registration is also
            // expected on main. configure() runs on Application.onCreate
            // so we are already on main.
            owner.lifecycle.addObserver(obs)
        } catch (e: Throwable) {
            Log.warning("SessionManager: ProcessLifecycleOwner unavailable (${e.message})")
        }
    }

    /** Detach the lifecycle observer. Useful for tests + `AppDNA.shutdown()`. */
    fun stop() {
        val obs = observer ?: return
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
        } catch (_: Throwable) {
            // ignore
        }
        observer = null
        synchronized(this) { started = false }
    }

    // MARK: - Lifecycle handlers (visible for testing)

    internal fun handleForeground() {
        // Skip every foreground until the app has been backgrounded at least once, so the
        // initial cold-launch foreground (start() already handled the session) fires neither app_open
        // nor a second rotation. Real background→foreground returns (hasBackgrounded == true) proceed.
        // Mirrors iOS, where willEnterForeground does not fire on a direct cold launch.
        if (!hasBackgrounded.get()) return
        val now = System.currentTimeMillis()
        val lastActive = lastActiveAtMs()
        if (lastActive != null && now - lastActive >= SESSION_TIMEOUT_MS) {
            // Old session expired while in background — close it and rotate.
            eventTracker.track("session_end", null)
            startNewSession()
        }
        eventTracker.track("app_open", null)
        persistLastActiveAt(now)
    }

    internal fun handleBackground() {
        // Mark that a background has occurred so the NEXT foreground is treated as a real return
        // (fires app_open + idle-rotation) rather than a cold-launch initial foreground.
        hasBackgrounded.set(true)
        eventTracker.track("app_close", null)
        persistLastActiveAt(System.currentTimeMillis())
    }

    // MARK: - Session rotation

    private fun startNewSession() {
        val newId = UUID.randomUUID().toString().lowercase()
        sessionIdRef.set(newId)
        val newCount = sessionsCountRef.incrementAndGet()
        sessionDataStore.setSessionData(KEY_SESSIONS_THIS_INSTALL, newCount)
        eventTracker.track("session_start", null)
        Log.info("SessionManager: started session $newId (#$newCount)")
    }

    private fun lastActiveAtMs(): Long? {
        return (sessionDataStore.getSessionData(KEY_LAST_ACTIVE_AT) as? Number)?.toLong()
    }

    private fun persistLastActiveAt(ms: Long) {
        sessionDataStore.setSessionData(KEY_LAST_ACTIVE_AT, ms)
    }
}
