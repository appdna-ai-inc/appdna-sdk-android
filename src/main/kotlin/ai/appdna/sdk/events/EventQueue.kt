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
 * SPEC-067: Adaptive batch sizing, deflate compression, SQLite persistence.
 * SPEC-070-A A.16: Differentiated retry policy (4xx drop, 5xx/429 retry-with-backoff,
 *   pause after 5 consecutive failures).
 * SPEC-070-A A.17: In-memory cap of 1000 events; oldest are evicted to disk.
 * SPEC-070-A A.18: Observe ProcessLifecycle ON_STOP → flush + schedule background uploader.
 *
 * Behavioural parity with iOS `Events/EventQueue.swift`.
 */
internal class EventQueue(
    private val apiClient: ApiClient,
    private val eventDatabase: EventDatabase,
    private val connectivityMonitor: ConnectivityMonitor?,
    private val batchSize: Int,
    private val flushInterval: Long, // seconds
    private val context: Context? = null,
    private val backgroundUploadScheduler: BackgroundUploadScheduler? = null
) {
    private val queue = CopyOnWriteArrayList<JSONObject>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    /** SPEC-070-A A.16: Track in-flight retry/backoff state. */
    private val flushMutex = Mutex()

    // SPEC-428 CL-2/D5: client redelivery horizon — never re-send an event older than this (past the
    // server dedup window it would double-count). Compiled default 7d, tracking SPEC-426's horizon.
    private val redeliveryHorizonMs = 7L * 24 * 60 * 60 * 1000
    @Volatile private var consecutiveFailures = 0
    @Volatile private var paused = false

    /** SPEC-070-A A.18: Lifecycle observer registered on ProcessLifecycleOwner.get(). */
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /** SPEC-067: Returns adaptive batch size based on network conditions. */
    private val effectiveBatchSize: Int
        get() = connectivityMonitor?.adaptiveBatchSize ?: batchSize

    init {
        // Load persisted events from SQLite
        val stored = eventDatabase.loadAll()
        for (json in stored) {
            try {
                queue.add(JSONObject(json))
            } catch (e: Exception) {
                // SPEC-070-A G.11: surface JSON parse errors instead of dropping
                // them silently. A bad row in SQLite usually means the writer
                // changed the envelope shape — we want to know about it.
                Log.warning { "Skipping persisted event with malformed JSON: ${e.message}" }
            }
        }
        if (stored.isNotEmpty()) {
            Log.info("Loaded ${stored.size} persisted events from SQLite")
        }

        // SPEC-070-A A.17: Trim in-memory cap if SQLite returned more than maxInMemoryEvents.
        // Disk still has them all; in-memory is just a sliding window of the most recent.
        trimInMemoryCap()

        // Start auto-flush timer
        startAutoFlush()

        // SPEC-070-A A.18: Wire ProcessLifecycleOwner so app backgrounding flushes
        // the queue + schedules the WorkManager uploader for whatever remains.
        registerLifecycleObserver()
    }

    /**
     * Secondary constructor for backward compatibility during migration.
     */
    constructor(
        apiClient: ApiClient,
        storage: LocalStorage,
        batchSize: Int,
        flushInterval: Long
    ) : this(apiClient, EventDatabase(storage.context), null, batchSize, flushInterval, null, null)

    /**
     * Add an event to the queue.
     */
    fun enqueue(event: JSONObject, onPersisted: (() -> Unit)? = null) {
        // SPEC-070-A A.17: Cap in-memory queue. Disk persistence happens regardless,
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
        // SPEC-067: Persist to SQLite (truth-of-record; survives process death)
        eventDatabase.insertEvent(event.toString())
        // SPEC-428 STEP-4: insertEvent is synchronous, so the event is durable here — safe to run the
        // dropped-meta counter decrement (only decrement after the meta is on disk, never a zero-reset).
        onPersisted?.invoke()

        // SPEC-067: Check adaptive threshold
        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize > 0 && queue.size >= currentBatchSize) {
            flush()
        }
    }

    /**
     * Force flush all queued events.
     * SPEC-070-A A.16: Manual flush also resets the "paused after N failures" gate.
     */
    fun flush() {
        scope.launch {
            // Manual flush implies user/UI intent — give the server another chance.
            paused = false
            consecutiveFailures = 0
            performFlushWithRetry()
        }
    }

    /**
     * SPEC-424 STEP-1a (CL-7): purge ALL pending events (in-memory + on-disk) WITHOUT uploading —
     * called when analytics consent is revoked so queued-but-unsent events are never transmitted.
     * A server-side consent gate is defeated if the SDK later flushes events captured while consent
     * was true, so revoke must drop them at the source.
     */
    fun clear() {
        queue.clear()
        eventDatabase.clearAll()
        Log.info("Event queue purged — analytics consent revoked")
    }

    /** SPEC-070-A A.18: Public hook for foreground events to retry the queue. */
    internal fun onAppForeground() {
        // Reset the pause gate on foreground (matches iOS "until next session" semantics).
        paused = false
        consecutiveFailures = 0
        flush()
    }

    /** SPEC-070-A A.18: Background hook — flush in-flight then hand off to WorkManager. */
    internal fun onAppBackground() {
        scope.launch {
            performFlushWithRetry()
            // Even if some events remain, hand off to WorkManager so they upload while suspended.
            backgroundUploadScheduler?.scheduleIfNeeded()
        }
    }

    private suspend fun performFlushWithRetry() {
        flushMutex.withLock {
            performFlushLocked()
        }
    }

    /**
     * SPEC-428 CL-2/D5: drop events past the redelivery horizon before flush — re-sending them past
     * the server dedup window would double-count. The drop is counted (CL-1).
     */
    private fun pruneStaleEvents() {
        val nowMs = System.currentTimeMillis()
        // Remove stale from the in-memory working set only (NO count here); eventDatabase.pruneStale is the
        // SINGLE, meta-aware count source (these events' persisted copies live on disk), so the loss metric
        // can't be double-incremented by the in-process flush AND the background upload both pruning.
        // SPEC-070-B PN row 19 (W14): the same clock-jump clamp the database uses — otherwise the
        // in-memory set and the disk set disagree about what is stale.
        val stale = queue.filter {
            ai.appdna.sdk.storage.EventDatabase.isStale(
                it.optLong("ts_ms", nowMs), nowMs, redeliveryHorizonMs
            )
        }
        if (stale.isNotEmpty()) queue.removeAll(stale.toSet())
        eventDatabase.pruneStale(redeliveryHorizonMs)
    }

    private suspend fun performFlushLocked() {
        if (paused) {
            Log.debug("Event flush paused after $consecutiveFailures consecutive failures — waiting for next foreground/manual flush")
            return
        }
        pruneStaleEvents()
        if (queue.isEmpty()) return

        val currentBatchSize = effectiveBatchSize
        if (currentBatchSize == 0) {
            Log.debug("No network — skipping flush, ${queue.size} events queued")
            return
        }

        // SPEC-428 CL-9/D4: single upload owner — skip if the background Worker holds the claim, so
        // the same rows are never POSTed by both paths concurrently. Released in the finally below.
        if (!EventUploadCoordinator.tryAcquire()) {
            Log.debug("Flush deferred — background uploader is active")
            return
        }
        try {

        val takeCount = currentBatchSize.coerceAtMost(queue.size)
        val batch = ArrayList(queue.take(takeCount))
        val batchArray = JSONArray()
        for (event in batch) batchArray.put(event)

        // SPEC-428 CL-4/D3: remove exactly the UPLOADED events by event_id (content-addressed), never
        // the DB's globally-oldest N — after a CL-1 in-mem eviction the in-mem batch diverges from the
        // oldest N, so deleting the oldest N drops never-uploaded rows (LOSS) + resends uploaded (DUP).
        val batchEventIds = batch.mapNotNull { runCatching { (it as JSONObject).getString("event_id") }.getOrNull() }.toSet()

        val body = JSONObject().apply { put("batch", batchArray) }.toString()

        // SPEC-070-A A.16 retry policy:
        // initial + up to MAX_RETRIES retries; backoff [1s, 2s, 4s] with ±25% jitter.
        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            val result = apiClient.postEventsBatch("/api/v1/ingest/events", body)
            when (result) {
                is EventUploadResult.Success -> {
                    queue.removeAll(batch.toSet())
                    eventDatabase.removeByEventIds(batchEventIds)
                    consecutiveFailures = 0
                    Log.debug("Flushed ${batch.size} events (deflate compressed)")
                    return
                }
                is EventUploadResult.ClientError -> {
                    // 4xx (except 429): payload/auth is broken — don't retry, drop the batch
                    // so the queue doesn't loop forever.
                    Log.error("Dropping batch of ${batch.size} events after HTTP ${result.statusCode} (4xx — retry won't help)")
                    // SPEC-428 D2/STEP-4: the WHOLE dropped batch is a loss (a 401 drops valid events; a
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
                    // 4xx is a permanent failure, not a transient one — count toward pause
                    bumpFailureCounter()
                    return
                }
                is EventUploadResult.TransientFailure -> {
                    attempt++
                    if (attempt > MAX_RETRIES) {
                        Log.warning("Flush exhausted ${MAX_RETRIES} retries; will try again on next flush cycle.")
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
                    Log.debug("Flush failed (transient HTTP=${result.statusCode}), retrying in ${delayMs}ms via $why (attempt $attempt/$MAX_RETRIES)")
                    delay(delayMs.coerceAtLeast(0L))
                }
            }
        }
        } finally {
            EventUploadCoordinator.release() // SPEC-428 CL-9: release the cross-path upload claim
        }
    }

    private fun bumpFailureCounter() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            paused = true
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
            // `flushInterval * 1000` overflowed for a very large interval (e.g. Long.MAX_VALUE, which
            // every test pipeline passes to mean "never") into a NEGATIVE delay — which returns at once,
            // so the loop spun on Dispatchers.IO flushing the database forever. Saturate instead.
            val intervalMs = if (flushInterval > Long.MAX_VALUE / 1000) Long.MAX_VALUE else flushInterval * 1000
            while (isActive) {
                delay(intervalMs)
                performFlushWithRetry()
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
                            // SPEC-070-A A.18: app entered background — flush + schedule WorkManager
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
        // SPEC-070-A finalization (Lens C P0) — cancel the scope itself so
        // any other launched coroutines (retry backoff, app-foreground flush)
        // are torn down too. Without this, SDK shutdown leaks IO threads.
        try { scope.cancel() } catch (_: Throwable) {}
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

    companion object {
        /** SPEC-070-A A.16: Max retries per batch. */
        const val MAX_RETRIES = 3
        /** SPEC-070-A A.16: Base backoff schedule before jitter. */
        val RETRY_DELAYS_MS: LongArray = longArrayOf(1000L, 2000L, 4000L)
        /** SPEC-070-A A.16: ±25 % jitter, matches the prompt requirement. */
        const val JITTER_PCT = 0.25

        /**
         * SPEC-070-B AC-35 (`backoff_bounded_and_jittered`) — apply ±[JITTER_PCT] to a backoff delay.
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
        /** SPEC-070-A A.16: Pause uploads after this many back-to-back batch failures. */
        const val MAX_CONSECUTIVE_FAILURES = 5
        /** SPEC-070-A A.17: Cap in-memory copy at 1000 events (matches iOS EventQueue.swift `maxInMemoryEvents`). */
        const val MAX_IN_MEMORY_EVENTS = 1000
    }
}

/**
 * SPEC-070-A A.18: Indirection for scheduling background uploads from EventQueue
 * without forcing a hard dependency on `background.EventUploadWorker`. Wired by
 * AppDNA.configure().
 */
internal interface BackgroundUploadScheduler {
    fun scheduleIfNeeded()
}

/**
 * SPEC-428 CL-9/D4 — process-wide single upload owner. The in-process flush (EventQueue) and the
 * background WorkManager uploader (EventUploadWorker) must be mutually exclusive, else both POST the
 * same rows concurrently (DUP). flushMutex is instance-scoped and does NOT guard the Worker; this
 * process-wide claim does. Non-blocking: whoever wins uploads, the other skips this cycle.
 */
internal object EventUploadCoordinator {
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryAcquire(): Boolean = uploading.compareAndSet(false, true)
    fun release() { uploading.set(false) }
}
