package ai.appdna.sdk.background

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.network.ApiClient
import ai.appdna.sdk.storage.EventDatabase
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SPEC-067: WorkManager-based background event upload.
 * Ensures queued events are delivered when the app is backgrounded or killed.
 */
internal class EventUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.info("Background event upload started")

        // SPEC-070-A H.24: own the EventDatabase handle for exactly the duration
        // of this run via try/finally so the SQLite file is always closed —
        // including on exception paths. Previously each WorkManager run leaked
        // a SQLiteOpenHelper.
        val eventDatabase = EventDatabase(applicationContext)
        try {
            return@withContext doUploadInner(eventDatabase)
        } finally {
            try { eventDatabase.close() } catch (_: Throwable) { /* close best-effort */ }
        }
    }

    private fun doUploadInner(eventDatabase: EventDatabase): Result {
        val apiKey = inputData.getString(KEY_API_KEY)
        val baseUrl = inputData.getString(KEY_BASE_URL)
        val paused = ai.appdna.sdk.events.UploadPauseGate.isPaused(applicationContext)
        val cap = ai.appdna.sdk.events.BatchSizeCapGate.cap(applicationContext)
        return when (uploadPending(eventDatabase, apiKey, baseUrl, paused, cap, applicationContext)) {
            Outcome.DONE -> Result.success()
            Outcome.RETRY -> Result.retry()
            Outcome.FAILED -> Result.failure()
        }
    }

    /** What one run of the uploader came to; [doWork] maps it to a WorkManager `Result`. */
    internal enum class Outcome { DONE, RETRY, FAILED }

    companion object {
        /** Batches one run uploads at most (100 events each) before it hands the rest to a new run. */
        internal const val MAX_BATCHES_PER_RUN = 50
        private const val BATCH_SIZE = 100

        /**
         * One run: upload the pending events batch by batch, until none is left ([Outcome.DONE]), a batch fails
         * transiently ([Outcome.RETRY]) or a batch is rejected permanently — dropped and counted; the run ends,
         * paused after a 401 / 403, otherwise retried for the rest ([uploadBatch]).
         *
         * When another owner holds the process-wide upload claim (the in-process flush — e.g. the final
         * attempt `AppDNA.shutdown()` makes), this run answers [Outcome.RETRY], so WorkManager runs it again
         * after its backoff. It used to answer success and do nothing; what the other owner did not send then
         * waited for the next `configure()`. A run also used to send ONE batch and succeed with the rest
         * still queued.
         */
        /**
         * Events per background upload: the network-agnostic 100, capped by the queue's persisted `batchSize`
         * cap ([ai.appdna.sdk.events.BatchSizeCapGate]) — the most the in-process queue sends at once too.
         */
        internal fun uploadBatchSize(cap: Int?): Int =
            ai.appdna.sdk.events.RuntimeSettings.effectiveBatchSize(BATCH_SIZE, cap)

        internal fun uploadPending(
            eventDatabase: EventDatabase,
            apiKey: String?,
            baseUrl: String?,
            paused: Boolean = false,
            batchSizeCap: Int? = null,
            /** Where a 401 / 403 sets the upload pause ([ai.appdna.sdk.events.UploadPauseGate]); null: nowhere. */
            context: Context? = null,
        ): Outcome {
            // The queue's failure pause holds for this uploader too ([ai.appdna.sdk.events.UploadPauseGate]): a
            // paused app uploads nothing until a foreground or `AppDNA.flush()`. Done, not retried — retrying
            // would only poll the pause; the next foreground flushes, and backgrounding schedules a fresh run.
            if (paused) {
                Log.debug("Background upload skipped — uploads are paused after repeated failures")
                return Outcome.DONE
            }
            // The host holds events on the device (batchSize 0): nothing to send, nothing to retry.
            val batchSize = uploadBatchSize(batchSizeCap)
            if (batchSize <= 0) {
                Log.debug("Background upload skipped — batch-size cap 0 (internal test seam) holds events on the device")
                return Outcome.DONE
            }
            // Single upload owner — never POST the rows the in-process flush is sending.
            if (!ai.appdna.sdk.events.EventUploadCoordinator.tryAcquire()) {
                Log.debug("Background upload deferred — another upload is active; retrying after backoff")
                return Outcome.RETRY
            }
            try {
                if (eventDatabase.count() == 0) {
                    Log.debug("No pending events for background upload")
                    return Outcome.DONE
                }
                // API key required; baseUrl required (no host fallback).
                if (apiKey == null) {
                    Log.error("Background upload: missing API key — refusing to upload")
                    return Outcome.FAILED
                }
                if (baseUrl == null) {
                    Log.error("Background upload: missing baseUrl input data — refusing to upload (set KEY_BASE_URL via scheduleIfNeeded)")
                    return Outcome.FAILED
                }
                // WorkManager can run hours/days after the events were queued — prune past
                // the redelivery horizon BEFORE upload so a stale event isn't re-sent past the server dedup
                // window (double-count). The in-process EventQueue prunes too; the store method covers both.
                eventDatabase.pruneStale(EventDatabase.REDELIVERY_HORIZON_MS)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                repeat(MAX_BATCHES_PER_RUN) {
                    val batch = eventDatabase.loadBatch(batchSize)
                    if (batch.isEmpty()) return Outcome.DONE
                    when (val o = uploadBatch(client, eventDatabase, batch, apiKey, baseUrl, context)) {
                        null -> Unit // sent; next batch
                        else -> return o
                    }
                }
                // More than one run's worth: a fresh run takes the rest.
                return if (eventDatabase.count() > 0) Outcome.RETRY else Outcome.DONE
            } finally {
                ai.appdna.sdk.events.EventUploadCoordinator.release()
            }
        }

        /** Upload one batch. Null: sent — go on with the next one; else the run's outcome. */
        private fun uploadBatch(
            client: okhttp3.OkHttpClient,
            eventDatabase: EventDatabase,
            batch: List<Pair<Long, String>>,
            apiKey: String,
            baseUrl: String,
            context: Context?,
        ): Outcome? {
            val batchArray = JSONArray()
            val eventIds = mutableListOf<String>()
            for ((_, eventJson) in batch) {
                try {
                    val obj = JSONObject(eventJson)
                    batchArray.put(obj)
                    obj.optString("event_id").takeIf { it.isNotEmpty() }?.let { eventIds += it }
                } catch (_: Exception) {}
            }
            val body = JSONObject().apply { put("batch", batchArray) }.toString()
            // Compress with deflate (parity with iOS APIClient.deflateCompress)
            val compressed = ApiClient.deflateCompress(body.toByteArray(Charsets.UTF_8))
            Log.debug("Background upload: ${batch.size} events, ${body.length} → ${compressed.size} bytes")
            return try {
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/api/v1/ingest/events")
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "deflate")
                    .header("Accept-Encoding", "gzip, deflate")
                    .post(compressed.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    // The one classifier the in-process queue and iOS use (408 / 429 / 5xx transient, any other
                    // 4xx permanent). This run had its own: a 408 was dropped as permanent here.
                    val disposition = ApiClient.dispositionFor(code)
                    if (disposition == ai.appdna.sdk.network.EventUploadDisposition.SUCCESS) {
                        eventDatabase.removeByIds(batch.map { it.first })
                        ai.appdna.sdk.events.EventUploadCoordinator.markResolved(eventIds)
                        Log.info("Background upload successful: ${batch.size} events")
                        null
                    } else if (disposition == ai.appdna.sdk.network.EventUploadDisposition.DROP_PERMANENT) {
                        Log.error("Background upload rejected (HTTP $code) — dropping batch (retry won't help)")
                        // The WHOLE dropped batch is a loss — count EVERY normal event (+1)
                        // AND re-add any _sdk_events_dropped meta's carried N, so nothing is silently lost on a
                        // background 4xx drop. A FRESH meta re-emits the total later. Mirrors EventQueue.
                        var loss = 0
                        for ((_, json) in batch) {
                            runCatching {
                                val obj = JSONObject(json)
                                loss += if (obj.optString("event_name") == "_sdk_events_dropped")
                                    obj.optJSONObject("properties")?.optInt("count", 0) ?: 0
                                else 1
                            }
                        }
                        if (loss > 0) ai.appdna.sdk.events.DroppedEventsCounter.increment(loss)
                        eventDatabase.removeByIds(batch.map { it.first })
                        ai.appdna.sdk.events.EventUploadCoordinator.markResolved(eventIds)
                        // The run ends here, as iOS `BackgroundUploader`'s does. A 401 / 403
                        // ([ApiClient.rejectionPausesUploads]) rejects every batch the same way: uploads pause — the
                        // in-process queue's rule — until the next foreground or `AppDNA.flush()`, and nothing is
                        // retried. Any other rejection was about this batch: a fresh run (after WorkManager's backoff)
                        // sends the events behind it. It used to go on at once with up to 50 batches, with no pause.
                        if (ApiClient.rejectionPausesUploads(code)) {
                            ai.appdna.sdk.events.UploadPauseGate.pauseFromBackgroundUpload(context)
                            Log.error("Background upload: the API key was rejected — uploads paused until the next foreground / AppDNA.flush()")
                            Outcome.DONE
                        } else if (eventDatabase.count() > 0) {
                            Outcome.RETRY
                        } else {
                            Outcome.DONE
                        }
                    } else {
                        Log.warning("Background upload transient failure (HTTP $code) — will retry")
                        Outcome.RETRY
                    }
                }
            } catch (e: Exception) {
                Log.error("Background upload error: ${e.message}")
                Outcome.RETRY
            }
        }

        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        private const val UNIQUE_WORK_NAME = "ai.appdna.sdk.eventUpload"

        /**
         * Test seam (null in production): the base URL every [scheduleIfNeeded] call receives — the SDK
         * must hand the worker the SAME resolved base as its `ApiClient`.
         */
        @Volatile internal var scheduleProbe: ((String) -> Unit)? = null

        /** The worker's input: the API key and the base URL [doWork] uploads to. */
        internal fun inputData(apiKey: String, baseUrl: String): Data = Data.Builder()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_BASE_URL, baseUrl)
            .build()

        /**
         * Schedule a background upload if there are pending events.
         */
        fun scheduleIfNeeded(
            context: Context,
            apiKey: String,
            baseUrl: String,
            eventDatabase: EventDatabase
        ) {
            scheduleProbe?.invoke(baseUrl)
            val pendingCount = eventDatabase.count()
            if (pendingCount == 0) return

            val inputData = inputData(apiKey, baseUrl)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadWork = OneTimeWorkRequestBuilder<EventUploadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    uploadWork
                )

            Log.info("Scheduled background upload for $pendingCount pending events")
        }
    }
}
