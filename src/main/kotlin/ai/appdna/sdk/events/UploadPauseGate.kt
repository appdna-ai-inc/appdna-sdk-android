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
 */
internal object UploadPauseGate {
    private const val PREFS = "ai.appdna.sdk.events"
    private const val KEY = "upload_paused"

    fun set(context: Context?, paused: Boolean) {
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
