package ai.appdna.sdk.events

import android.content.Context

/**
 * The event queue's failure pause (paused after [EventQueue.MAX_CONSECUTIVE_FAILURES] failed upload cycles),
 * persisted so the WorkManager uploader can honour it.
 *
 * The pause lives in the queue's memory. The uploader runs outside the queue — WorkManager can even start it
 * in a fresh process where no queue exists — and used to upload whatever the pause said, so backgrounding a
 * paused app (or the upload `shutdown()` hands over) started exactly the uploads the pause stops. The queue
 * writes the gate when it pauses and when a foreground or `AppDNA.flush()` clears the pause, and clears it
 * when a new queue starts (a new `configure()` starts unpaused, in memory and here alike); the uploader reads
 * it before it uploads anything. iOS `UploadPauseGate`, same rule.
 *
 * One queue owns the gate at a time: the newest. A queue [claim]s it when it starts and writes with the owner
 * token it got back; a write from any other token is ignored. A queue `shutdown()` ended can still fail its
 * final upload after the next `configure()`'s queue cleared the gate — that stale write used to set the pause
 * for the new session, and the uploader then skipped its runs.
 */
internal object UploadPauseGate {
    private const val PREFS = "ai.appdna.sdk.events"
    private const val KEY = "upload_paused"
    private var owner = 0L

    /** A new queue takes the gate: it is cleared, and from now on only the returned owner's writes count. */
    @Synchronized
    fun claim(context: Context?): Long {
        owner += 1
        write(context, false)
        return owner
    }

    /** Set the gate — if [ownerToken] still owns it (a queue that a newer one replaced writes nothing). */
    @Synchronized
    fun set(context: Context?, paused: Boolean, ownerToken: Long) {
        if (ownerToken != owner) {
            ai.appdna.sdk.Log.debug("Upload pause from a replaced event queue ignored")
            return
        }
        write(context, paused)
    }

    /**
     * The WorkManager uploader's pause after a 401 / 403 ([ai.appdna.sdk.network.ApiClient.rejectionPausesUploads]).
     * It owns no token — it may run in a process with no queue — so it sets the gate whoever owns it; the owning
     * queue clears it at the next foreground or `AppDNA.flush()`, and a new queue clears it when it claims the gate.
     */
    @Synchronized
    fun pauseFromBackgroundUpload(context: Context?) = write(context, true)

    /** Test seam: set the gate whoever owns it. */
    @Synchronized
    fun setForTest(context: Context?, paused: Boolean) = write(context, paused)

    private fun write(context: Context?, paused: Boolean) {
        val ctx = context ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, paused).apply()
        } catch (_: Throwable) { /* best effort: the in-memory pause still holds for the queue */ }
    }

    fun isPaused(context: Context?): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        } catch (_: Throwable) { false }
    }
}
