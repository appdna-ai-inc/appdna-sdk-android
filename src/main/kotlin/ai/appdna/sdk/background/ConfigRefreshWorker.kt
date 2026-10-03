package ai.appdna.sdk.background

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic remote-config refresh via WorkManager.
 *
 * Runs every 15 minutes (Android's minimum periodic interval) when the app
 * is connected, ensuring the cached config never drifts further than a
 * single interval from server state — matching the iOS background refresh
 * cadence (`PeriodicConfigRefresh` in `RemoteConfigManager.swift`).
 *
 * The work is best-effort: if the SDK isn't configured (e.g. between
 * Application onCreate races), or the manager is null, the worker
 * succeeds silently and waits for the next tick.
 */
internal class ConfigRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val mgr = AppDNA.remoteConfig.manager
            if (mgr == null) {
                Log.debug("ConfigRefreshWorker: SDK not configured yet — skipping")
                return@withContext Result.success()
            }
            mgr.fetchConfigs()
            Log.debug("ConfigRefreshWorker: refreshed remote config")
            Result.success()
        } catch (e: Throwable) {
            Log.warning("ConfigRefreshWorker: refresh failed: ${e.message}")
            // Use retry so transient backend hiccups don't skip a 15-minute slot.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ai.appdna.sdk.configRefresh"

        /**
         * Schedule (or replace) the periodic refresh. Idempotent — the
         * `ExistingPeriodicWorkPolicy.KEEP` ensures repeated configure()
         * calls don't pile up duplicate workers.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<ConfigRefreshWorker>(
                    15, TimeUnit.MINUTES,
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )

                Log.info("ConfigRefreshWorker: scheduled 15-minute periodic refresh")
            } catch (e: Throwable) {
                Log.warning("ConfigRefreshWorker: failed to schedule: ${e.message}")
            }
        }

        /** Cancel the periodic refresh (used by `AppDNA.shutdown`). */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            } catch (_: Throwable) { /* WorkManager may not be initialized */ }
        }
    }
}
