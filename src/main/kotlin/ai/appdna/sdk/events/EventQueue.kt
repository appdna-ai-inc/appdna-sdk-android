package ai.appdna.sdk.events

import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.network.ConnectivityMonitor
import ai.appdna.sdk.network.EventUploadResult
import ai.appdna.sdk.storage.EventDatabase
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Manages in-memory + disk event queue with automatic flushing.
 *
 * Adaptive batch sizing, deflate compression, SQLite persistence.
 * Differentiated retry policy (4xx drop, 5xx/429 retry-with-backoff,
 *   pause after 5 consecutive failures).
 * In-memory cap of 1000 events; oldest are evicted to disk.
 * Observe ProcessLifecycle ON_STOP → flush + schedule background uploader.
 *
 * Behavioural parity with iOS `Events/EventQueue.swift`.
 */
internal class EventQueue(
    private val apiClient: ApiClient,
    private val eventDatabase: EventDatabase,
    private val connectivityMonitor: ConnectivityMonitor?,
    batchSizeCap: Int?,
    flushInterval: Long, // seconds
    private val context: Context? = null,
    private val backgroundUploadScheduler: BackgroundUploadScheduler? = null
) {
    private val queue = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** The one final upload attempt [shutdown] makes, after [scope] is cancelled. */
    private val shutdownFlushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null
    /** Counts down when the final flush [shutdown] starts has finished, scheduled and closed the database. */
    internal val shutdownFlushDone = java.util.concurrent.CountDownLatch(1)

    /** Track in-flight retry/backoff state. */
    private val flushMutex = Mutex()

    // Client redelivery horizon — never re-send an event older than this (past the
    // server dedup window it would double-count). Compiled default 7d, tracking the horizon.
    private val redeliveryHorizonMs = 7L * 24 * 60 * 60 * 1000
    @Volatile private var consecutiveFailures = 0
    @Volatile private var paused = false
    /** This queue's token for [UploadPauseGate] (see [UploadPauseGate.claim]). */
    private val pauseOwner: Long

    /** Lifecycle observer registered on ProcessLifecycleOwner.get(). */
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /** The host's / the bootstrap's cap on the adaptive batch size ([RuntimeSettings]); null: no cap. */
    @Volatile private var batchSizeCap: Int? = batchSizeCap
    /** Seconds between scheduled flushes. */
    @Volatile private var flushInterval: Long = flushInterval

    /**
     * The current effective batch size — the network-sized one (100 without a connectivity
     * monitor), capped by [batchSizeCap].
     */
    private val effectiveBatchSize: Int
        get() = RuntimeSettings.effectiveBatchSize(
            connectivityMonitor?.adaptiveBatchSize ?: NO_MONITOR_ADAPTIVE_BATCH_SIZE, batchSizeCap,
        )

    /** Test readers: the cap and interval in force, and the effective batch size. */
    internal val batchSizeCapForTesting: Int? get() = batchSizeCap
    internal val flushIntervalForTesting: Long get() = flushInterval
    internal val effectiveBatchSizeForTesting: Int get() = effectiveBatchSize

    /**
     * Apply runtime settings resolved after a bootstrap ([RuntimeSettings]): the cap takes effect at the next
     * threshold check and upload (and in the WorkManager uploader); a changed interval restarts the timer.
     */
    fun applyRuntimeSettings(batchSizeCap: Int?, flushInterval: Long) {
        this.batchSizeCap = batchSizeCap
        BatchSizeCapGate.set(context, batchSizeCap)
        if (this.flushInterval != flushInterval) {
            this.flushInterval = flushInterval
            if (scope.isActive) {
                flushJob?.cancel()
                startAutoFlush()
            }
        }
    }

    init {
        // A new queue (a new `configure()`) starts unpaused; so does the gate the background uploader reads,
        // which this queue owns from now on (a queue ended before it can no longer write it).
        pauseOwner = UploadPauseGate.claim(context)
        // The background uploader sends batches no larger than this queue does.
        BatchSizeCapGate.set(context, batchSizeCap)
        // Load persisted events from SQLite — only the in-memory window (the newest MAX_IN_MEMORY_EVENTS;
        // SQLite keeps them all). This runs inside `configure()`, on the caller's thread: reading and parsing
        // every stored row made a 10,000-event backlog a 10,000-row parse there.
        val stored = eventDatabase.loadNewest(MAX_IN_MEMORY_EVENTS)
        for (json in stored) {
            try {
                queue.add(JSONObject(json))
            } catch (e: Exception) {
                // Surface JSON parse errors instead of dropping
                // them silently. A bad row in SQLite usually means the writer
                // changed the envelope shape — we want to know about it.
                Log.warning { "Skipping persisted event with malformed JSON: ${e.message}" }
            }
        }
        if (stored.isNotEmpty()) {
            Log.info("Loaded ${stored.size} persisted events from SQLite")
        }

        // Trim in-memory cap if SQLite returned more than maxInMemoryEvents.
        // Disk still has them all; in-memory is just a sliding window of the most recent.
        trimInMemoryCap()

        // Start auto-flush timer
        startAutoFlush()

        // Wire ProcessLifecycleOwner so app backgrounding flushes
        // the queue + schedules the WorkManager uploader for whatever remains.
        registerLifecycleObserver()
    }

    /**
     * Secondary constructor for backward compatibility during migration.
     */
    constructor(
        apiClient: ApiClient,
        storage: LocalStorage,
        batchSizeCap: Int?,
        flushInterval: Long
    ) : this(apiClient, EventDatabase(storage.context), null, batchSizeCap, flushInterval, null, null)

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: JSONObject, onPersisted: (() -> Unit)? = null) {
        // Cap in-memory queue. Disk persistence happens regardless,
        // so evicted events are not lost — just dropped from RAM.
        if (queue.size >= MAX_IN_MEMORY_EVENTS) {
            // Evict the oldest entries down to (cap - 1) so the new event fits.
            val excess = queue.size - MAX_IN_MEMORY_EVENTS + 1
            for (i in 0 until excess) {
                if (queue.isNotEmpty()) queue.removeAt(0)
            }
            Log.debug("In-memory event cap hit ($MAX_IN_MEMORY_EVENTS) — evicted $excess oldest events from RAM (still on disk)")
        }
        queue.add(event)
        // Persist to SQLite (truth-of-record; survives process death)
        eventDatabase.insertEvent(event)
        // insertEvent is synchronous, so the event is durable here — safe to run the
        // dropped-meta counter decrement (only decrement after the meta is on disk, never a zero-reset).
        onPersisted?.invoke()

        // Check adaptive threshold. A threshold flush KEEPS the failure pause: it used to call
        // the public [flush], which clears it, so during an outage every `track()` that reached the batch
        // size ran a full 1 + 3-attempt upload cycle. iOS keeps the pause here too.
        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize > 0 && queue.size >= currentBatchSize) {
            flush(FlushTrigger.THRESHOLD)
        }
    }

    /**
     * Who asked for a flush — and so whether it clears the failure pause ([paused] after
     * [MAX_CONSECUTIVE_FAILURES]). Only the app coming to the foreground and the host's own
     * `AppDNA.flush()` give a paused queue another chance; the batch threshold, the interval timer,
     * backgrounding and [shutdown] keep the pause. iOS `EventQueue.FlushTrigger` is the same table; the
     * shared `flush_pause_gate` fixture asserts both.
     */
    internal enum class FlushTrigger(val clearsPauseGate: Boolean) {
        THRESHOLD(false),
        SCHEDULED(false),
        BACKGROUND(false),
        SHUTDOWN(false),
        FOREGROUND(true),
        EXPLICIT(true),
    }

    /**
     * Force flush all queued events — the host's `AppDNA.flush()`.
     * Manual flush also resets the "paused after N failures" gate.
     */
    fun flush() = flush(FlushTrigger.EXPLICIT)

    internal fun flush(trigger: FlushTrigger) {
        scope.launch { runFlush(trigger) }
    }

    private suspend fun runFlush(trigger: FlushTrigger, maxRetries: Int = MAX_RETRIES) {
        if (trigger.clearsPauseGate) {
            paused = false
            consecutiveFailures = 0
            UploadPauseGate.set(context, false, pauseOwner)
        }
        flushMutex.withLock { performFlushLocked(maxRetries) }
    }

    /**
     * Purge ALL pending events (in-memory + on-disk) WITHOUT uploading
     * called when analytics consent is revoked so queued-but-unsent events are never transmitted.
     * A server-side consent gate is defeated if the SDK later flushes events captured while consent
     * was true, so revoke must drop them at the source.
     */
    fun clear() {
        queue.clear()
        eventDatabase.clearAll()
        Log.info("Event queue purged — analytics consent revoked")
    }

    /** Public hook for foreground events to retry the queue. */
    internal fun onAppForeground() {
        // Reset the pause gate on foreground (matches iOS "until next session" semantics).
        flush(FlushTrigger.FOREGROUND)
    }

    /** Background hook — flush in-flight then hand off to WorkManager. */
    internal fun onAppBackground() {
        scope.launch {
            runFlush(FlushTrigger.BACKGROUND)
            // Even if some events remain, hand off to WorkManager so they upload while suspended — unless the
            // queue is paused: backgrounding keeps the pause, and a background upload would bypass it.
            if (paused) {
                Log.debug("Uploads paused — no background upload scheduled until the next foreground / AppDNA.flush()")
            } else {
                backgroundUploadScheduler?.scheduleIfNeeded()
            }
        }
    }

    /**
     * Drop events past the redelivery horizon before flush — re-sending them past
     * the server dedup window would double-count. The drop is counted.
     */
    private fun pruneStaleEvents() {
        val nowMs = System.currentTimeMillis()
        // Remove stale from the in-memory working set only (NO count here); eventDatabase.pruneStale is the
        // SINGLE, meta-aware count source (these events' persisted copies live on disk), so the loss metric
        // can't be double-incremented by the in-process flush AND the background upload both pruning.
        // The same clock-jump clamp the database uses — otherwise the
        // in-memory set and the disk set disagree about what is stale.
        val stale = queue.filter {
            ai.appdna.sdk.storage.EventDatabase.isStale(
                it.optLong("ts_ms", nowMs), nowMs, redeliveryHorizonMs
            )
        }
        if (stale.isNotEmpty()) queue.removeAll(stale.toSet())
        eventDatabase.pruneStale(redeliveryHorizonMs)
    }

    private suspend fun performFlushLocked(maxRetries: Int = MAX_RETRIES) {
        if (paused) {
            Log.debug("Event flush paused after $consecutiveFailures consecutive failures — waiting for next foreground/manual flush")
            return
        }
        pruneStaleEvents()
        if (queue.isEmpty()) reloadWindowFromDisk()
        if (queue.isEmpty()) return

        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize == 0) {
            Log.debug("No network — skipping flush, ${queue.size} events queued")
            return
        }

        // Single upload owner — skip if the background Worker holds the claim, so
        // the same rows are never POSTed by both paths concurrently. Released in the finally below.
        if (!EventUploadCoordinator.tryAcquire()) {
            Log.debug("Flush deferred — background uploader is active")
            return
        }
        try {
        // Events another upload owner already resolved — the WorkManager uploader, or the final flush of a
        // queue that `shutdown()` ended while this one loaded the same rows from SQLite — are not sent
        // again: that owner removed them from disk, but this queue still holds its in-memory copies.
        // Removed from SQLite too: the other owner's removal may not have reached it.
        val resolvedElsewhere = queue.filter { EventUploadCoordinator.wasResolved(it.optString("event_id")) }
        if (resolvedElsewhere.isNotEmpty()) {
            queue.removeAll(resolvedElsewhere.toSet())
            eventDatabase.removeByEventIds(resolvedElsewhere.mapTo(HashSet()) { it.optString("event_id") })
            Log.debug("Skipped ${resolvedElsewhere.size} event(s) another upload already delivered")
        }
        if (queue.isEmpty()) return

        val takeCount = currentBatchSize.coerceAtMost(queue.size)
        val batch = ArrayList(queue.take(takeCount))
        val batchArray = JSONArray()
        for (event in batch) batchArray.put(event)

        // Remove exactly the UPLOADED events by event_id (content-addressed), never
        // the DB's globally-oldest N — after a CL-1 in-mem eviction the in-mem batch diverges from the
        // oldest N, so deleting the oldest N drops never-uploaded rows (LOSS) + resends uploaded (DUP).
        val batchEventIds = batch.mapNotNull { runCatching { (it as JSONObject).getString("event_id") }.getOrNull() }.toSet()

        val body = JSONObject().apply { put("batch", batchArray) }.toString()

        // Retry policy:
        // initial + up to MAX_RETRIES retries; backoff [1s, 2s, 4s] with ±25% jitter.
        var attempt = 0
        while (attempt <= maxRetries) {
            // NonCancellable: `shutdown()` cancels [scope] while a flush's request may be in flight. The
            // request is blocking and completes anyway; a cancellable call then threw on return, so a batch
            // the server had accepted was neither removed from memory nor from SQLite, and the final flush
            // sent it again. The answer is always applied.
            val result = withContext(NonCancellable) { apiClient.postEventsBatch("/api/v1/ingest/events", body) }
            when (result) {
                is EventUploadResult.Success -> {
                    queue.removeAll(batch.toSet())
                    eventDatabase.removeByEventIds(batchEventIds)
                    EventUploadCoordinator.markResolved(batchEventIds)
                    consecutiveFailures = 0
                    Log.debug("Flushed ${batch.size} events (deflate compressed)")
                    return
                }
                is EventUploadResult.ClientError -> {
                    // 4xx (except 429): payload/auth is broken — don't retry, drop the batch
                    // so the queue doesn't loop forever.
                    Log.error("Dropping batch of ${batch.size} events after HTTP ${result.statusCode} (4xx — retry won't help)")
                    // The WHOLE dropped batch is a loss (a 401 drops valid events; a
                    // 400 drops malformed ones) — count EVERY normal event (+1) AND re-add any
                    // _sdk_events_dropped meta's carried N, so NOTHING is silently lost. A fresh meta
                    // (new event_id) re-emits the total when uploads resume; bumpFailureCounter pauses,
                    // bounding any retry.
                    var loss = 0
                    for (e in batch) {
                        loss += if (e.optString("event_name") == "_sdk_events_dropped")
                            e.optJSONObject("properties")?.optInt("count", 0) ?: 0
                        else 1
                    }
                    if (loss > 0) DroppedEventsCounter.increment(loss)
                    queue.removeAll(batch.toSet())
                    eventDatabase.removeByEventIds(batchEventIds)
                    EventUploadCoordinator.markResolved(batchEventIds)
                    // 4xx is a permanent failure, not a transient one — count toward pause. A 401 / 403
                    // ([ApiClient.rejectionPausesUploads]) is about the key, not the batch: every later batch would
                    // be rejected and dropped the same way, so uploads pause now (the WorkManager uploader too,
                    // through the gate) until the next foreground or `AppDNA.flush()`. iOS `EventQueue`, same rule.
                    if (ApiClient.rejectionPausesUploads(result.statusCode)) {
                        consecutiveFailures = maxOf(consecutiveFailures, MAX_CONSECUTIVE_FAILURES - 1)
                    }
                    bumpFailureCounter()
                    return
                }
                is EventUploadResult.TransientFailure -> {
                    attempt++
                    if (attempt > maxRetries) {
                        Log.warning("Flush exhausted $maxRetries retries; will try again on next flush cycle.")
                        bumpFailureCounter()
                        return
                    }
                    // A server-supplied Retry-After wins over our backoff schedule.
                    // Otherwise: exponential base + ±25% jitter, so a fleet throttled
                    // together does not retry in lockstep.
                    val serverHintMs = result.retryAfterSeconds?.times(1000L)
                    val delayMs = serverHintMs
                        ?: jittered(RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.size - 1)])
                    val why = if (serverHintMs != null) "Retry-After" else "backoff"
                    Log.debug("Flush failed (transient HTTP=${result.statusCode}), retrying in ${delayMs}ms via $why (attempt $attempt/$maxRetries)")
                    delay(delayMs.coerceAtLeast(0L))
                }
            }
        }
        } finally {
            EventUploadCoordinator.release() // Release the cross-path upload claim
        }
    }

    /**
     * The in-memory window is drained, but SQLite may hold more — the older rows of a backlog larger than the
     * window (the constructor loads only the newest [MAX_IN_MEMORY_EVENTS]), or events that left the window when
     * it was full. Load the oldest of them, so the in-process queue keeps sending them. Before, a drained window
     * stayed empty until the next `track()` or launch, and only the WorkManager uploader sent the rest. Under
     * [flushMutex].
     *
     * Events another owner already resolved are not loaded — and are removed from SQLite here: they can still be
     * stored when that owner's removal did not reach the database. Left there, a front of [MAX_IN_MEMORY_EVENTS] of
     * them made every reload find nothing, and the rows behind them were never sent by the queue. iOS
     * `EventQueue.reloadWindowFromDisk`, same rule.
     */
    private fun reloadWindowFromDisk() {
        // Each pass removes what it skips, so the next one reads further; the bound only guards a database that keeps
        // failing to delete (each pass is one window).
        repeat(MAX_RELOAD_PASSES) {
            if (eventDatabase.count() == 0) return
            val rows = eventDatabase.loadBatch(MAX_IN_MEMORY_EVENTS)
            // A `track()` racing this reload is already in memory (and may be in `rows` too): never twice.
            val present = queue.mapTo(HashSet()) { it.optString("event_id") }
            val older = ArrayList<JSONObject>(rows.size)
            val resolved = HashSet<String>()
            for ((_, json) in rows) {
                val obj = try { JSONObject(json) } catch (_: Exception) { continue }
                val id = obj.optString("event_id")
                if (EventUploadCoordinator.wasResolved(id)) { resolved += id; continue }
                if (id in present) continue
                older.add(obj)
            }
            if (resolved.isNotEmpty()) {
                eventDatabase.removeByEventIds(resolved)
                Log.debug("Removed ${resolved.size} stored event(s) another upload already delivered")
            }
            if (older.isNotEmpty()) {
                queue.addAll(0, older)
                trimInMemoryCap()
                Log.debug("Reloaded ${older.size} persisted events into the drained in-memory window")
                return
            }
            if (resolved.isEmpty()) return
        }
    }

    /** Test reader: the events in the in-memory window. */
    internal val inMemoryCountForTest: Int get() = queue.size

    private fun bumpFailureCounter() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            paused = true
            UploadPauseGate.set(context, true, pauseOwner)
            Log.warning("Too many consecutive flush failures ($consecutiveFailures). Event uploads paused until next foreground/manual flush.")
        }
    }

    private fun trimInMemoryCap() {
        while (queue.size > MAX_IN_MEMORY_EVENTS && queue.isNotEmpty()) {
            queue.removeAt(0)
        }
    }

    private fun startAutoFlush() {
        flushJob = scope.launch {
            val intervalMs = autoFlushIntervalMs(flushInterval)
            while (isActive) {
                delay(intervalMs)
                runFlush(FlushTrigger.SCHEDULED)
            }
        }
    }

    private fun registerLifecycleObserver() {
        if (context == null) return
        // ProcessLifecycleOwner must be observed on the main thread.
        // We post to the main thread so the observer is registered safely
        // regardless of which thread constructed EventQueue.
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val obs = object : DefaultLifecycleObserver {
                        override fun onStop(owner: LifecycleOwner) {
                            // App entered background — flush + schedule WorkManager
                            onAppBackground()
                        }

                        override fun onStart(owner: LifecycleOwner) {
                            // Foreground — re-arm flush (resets pause counter).
                            onAppForeground()
                        }
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
                    lifecycleObserver = obs
                } catch (e: Throwable) {
                    Log.warning("Could not register ProcessLifecycleOwner observer: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("Lifecycle observer scheduling failed: ${e.message}")
        }
    }

    fun shutdown() {
        flushJob?.cancel()
        // Cancel the scope itself so
        // any other launched coroutines (retry backoff, app-foreground flush)
        // are torn down too. Without this, SDK shutdown leaks IO threads.
        try { scope.cancel() } catch (_: Throwable) {}
        // One last upload attempt of the queued events — the same one attempt iOS makes in
        // `AppDNA.shutdown()` — outside the cancelled scope, with no retry backoff, so it ends with its
        // one request (bounded by the client's call timeout). It keeps the failure pause and holds the
        // single-upload claim while it runs. When it has finished — the claim released — it hands what is
        // left in SQLite to the WorkManager uploader, and only then closes the database: this attempt owns
        // the handle after `shutdown()`. Scheduling the worker earlier (at `shutdown()`) let it run while
        // the claim was still held; it skipped, and the rest waited for the next `configure`. Closing the
        // database earlier made this attempt reopen it (a connection nobody closed) or fail its removal
        // (the batch was then sent again).
        shutdownFlushScope.launch {
            try {
                runFlush(FlushTrigger.SHUTDOWN, maxRetries = 0)
            } catch (e: Throwable) {
                Log.debug("Final flush on shutdown failed: ${e.message}")
            } finally {
                // Not while paused: the pause holds until a foreground or `AppDNA.flush()` (the next session's).
                if (!paused) try { backgroundUploadScheduler?.scheduleIfNeeded() } catch (e: Throwable) {
                    Log.warning("Background upload schedule after the final flush failed: ${e.message}")
                }
                try { eventDatabase.close() } catch (_: Throwable) {}
                shutdownFlushDone.countDown()
            }
        }
        // Detach lifecycle observer (must run on main thread).
        val obs = lifecycleObserver
        if (obs != null) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            lifecycleObserver = null
        }
        // Events are already persisted to SQLite on enqueue
    }

    /** Test seam: the state after [MAX_CONSECUTIVE_FAILURES] failed cycles (the pause, and its persisted gate). */
    internal fun pauseForTest() {
        consecutiveFailures = MAX_CONSECUTIVE_FAILURES
        paused = true
        UploadPauseGate.set(context, true, pauseOwner)
    }

    /** Test seam: one upload cycle with no retries (the cycle `shutdown()` makes); a failure counts toward the pause. */
    internal suspend fun flushCycleForTest() = runFlush(FlushTrigger.SCHEDULED, maxRetries = 0)

    /** Test reader: failed upload cycles in a row (the queue pauses at [MAX_CONSECUTIVE_FAILURES]). */
    internal val consecutiveFailuresForTest: Int get() = consecutiveFailures

    /** Test reader: the database this queue persists to (and closes after its final flush). */
    internal val eventDatabaseForTest: EventDatabase get() = eventDatabase

    /** Test seam: wait for the final flush [shutdown] started. True when it has finished. */
    internal fun awaitShutdownFlush(timeoutMs: Long): Boolean =
        shutdownFlushDone.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    companion object {
        /** The adaptive size a queue built without a connectivity monitor (tests, legacy constructor) uses. */
        internal const val NO_MONITOR_ADAPTIVE_BATCH_SIZE = 100

        /**
         * The auto-flush period in ms for an interval in seconds. `flushInterval * 1000` overflowed for
         * a very large interval (e.g. Long.MAX_VALUE, which every test pipeline passes to mean "never")
         * into a NEGATIVE delay — which returns at once, so the loop spun on Dispatchers.IO flushing the
         * database forever: saturate. An interval of 0 or less spun the same way (`delay(0)`): at least
         * one second.
         */
        internal fun autoFlushIntervalMs(flushIntervalSeconds: Long): Long {
            val seconds = flushIntervalSeconds.coerceAtLeast(1)
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }

        /** Max retries per batch. */
        const val MAX_RETRIES = 3
        /** Base backoff schedule before jitter. */
        val RETRY_DELAYS_MS: LongArray = longArrayOf(1000L, 2000L, 4000L)
        /** ±25 % jitter, matches the prompt requirement. */
        const val JITTER_PCT = 0.25

        /**
         * (`backoff_bounded_and_jittered`) — apply ±[JITTER_PCT] to a backoff delay.
         *
         * A named seam, not four lines inlined in the retry loop, because the fixture has to be able
         * to SAMPLE it: "bounded" and "jittered" are claims about a distribution, and a distribution
         * cannot be asserted through a `delay()` call inside a coroutine that is talking to OkHttp.
         * Mirrors iOS `EventQueue.jittered(_:)`, which the same fixture drives.
         *
         * Never negative: a clamped-to-zero delay retries immediately, which is bad; a NEGATIVE delay
         * would throw.
         */
        @JvmStatic
        fun jittered(baseMs: Long): Long {
            val jitterRange = (baseMs * JITTER_PCT).toLong()
            if (jitterRange <= 0L) return baseMs.coerceAtLeast(0L)
            return (baseMs + Random.nextLong(-jitterRange, jitterRange + 1)).coerceAtLeast(0L)
        }
        /** Pause uploads after this many back-to-back batch failures. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        /** Window reloads one drain makes at most ([reloadWindowFromDisk]). */
        internal const val MAX_RELOAD_PASSES = 20
        /** Cap in-memory copy at 1000 events (matches iOS EventQueue.swift `maxInMemoryEvents`). */
        const val MAX_IN_MEMORY_EVENTS = 1000
    }
}

/**
 * Indirection for scheduling background uploads from EventQueue
 * without forcing a hard dependency on `background.EventUploadWorker`. Wired by
 * AppDNA.configure().
 */
internal interface BackgroundUploadScheduler {
    fun scheduleIfNeeded()
}

/**
 * Process-wide single upload owner. The in-process flush (EventQueue) and the
 * background WorkManager uploader (EventUploadWorker) must be mutually exclusive, else both POST the
 * same rows concurrently (DUP). flushMutex is instance-scoped and does NOT guard the Worker; this
 * process-wide claim does. Non-blocking: whoever wins uploads, the other skips this cycle.
 */
internal object EventUploadCoordinator {
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryAcquire(): Boolean = uploading.compareAndSet(false, true)
    fun release() { uploading.set(false) }

    /**
     * The `event_id`s an upload owner resolved (delivered, or dropped on a 4xx) in this process, most
     * recent [MAX_RESOLVED]. The claim stops two owners POSTing at once, not one owner re-sending what
     * the other has already sent: a queue holds in-memory copies of the rows it loaded from SQLite, and
     * the WorkManager uploader — or the final flush of a queue `shutdown()` ended, while the next
     * `configure()`'s queue loaded the same rows — removes them from disk only. Every owner records what
     * it resolved here, while it holds the claim; a queue drops those from memory before it uploads.
     */
    const val MAX_RESOLVED = 10_000
    private val resolved = LinkedHashSet<String>()

    @Synchronized
    fun markResolved(eventIds: Collection<String>) {
        for (id in eventIds) {
            if (id.isEmpty()) continue
            resolved.remove(id)
            resolved.add(id)
        }
        val it = resolved.iterator()
        while (resolved.size > MAX_RESOLVED && it.hasNext()) { it.next(); it.remove() }
    }

    @Synchronized
    fun wasResolved(eventId: String): Boolean = eventId.isNotEmpty() && eventId in resolved

    @Synchronized
    internal fun clearResolvedForTest() = resolved.clear()
}
