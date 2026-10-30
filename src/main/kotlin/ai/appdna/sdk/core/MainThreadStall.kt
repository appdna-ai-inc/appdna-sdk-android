package ai.appdna.sdk.core

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.appdna.sdk.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NAME THE CALL THAT HELD THE MAIN THREAD, instead of learning afterwards that something did.
 *
 * A customer's logcat carried two lines from the system and nothing from us:
 *
 *     W/ActivityTaskManager  Activity top resumed state loss timeout for … PaywallActivity
 *     I/wm_failed_to_pause   [0,…,…/ai.appdna.sdk.paywalls.PaywallActivity,(none)]
 *
 * Both mean the same thing: `onPause` did not return inside the window the system allows, so the
 * main thread was busy when it should have been free. An activity that cannot pause is the shape
 * the system escalates — a dropped frame budget first, then an ANR, then a kill.
 *
 * 🔴 WHAT THE LOG COULD NOT SAY. It records that a deadline was missed. It does not record what was
 * running, so every candidate had to be ruled out by READING — and reading is how a main-thread
 * stall gets written in the first place.
 *
 * 🔴 AND THE FIRST VERSION OF THIS FILE REPRODUCED THAT EXACT LIMITATION. It watched the
 * `Choreographer` and, on noticing a long gap, captured the stack at the FIRST FRAME AFTER it. That
 * stack is always the same thing — `Choreographer.doFrame → Looper.loop → ActivityThread.main` —
 * because by the time a frame runs again the blocking work has returned. It was measured on a real
 * reproduction: a 1196 ms stall, 71 skipped frames, and a stack naming nothing.
 *
 * So the sample is taken DURING the stall, from a watchdog thread:
 *
 *   1. post a ping to the main looper and note the time;
 *   2. sleep past the deadline;
 *   3. if the ping has not run, the main thread is still inside something — `Thread.getStackTrace`
 *      on it is a live sample of what.
 *
 * That is the difference between "something was slow" and "this method was slow", which is the
 * sentence the issue asks for.
 *
 * 🔴 NOT StrictMode, which was the other idea. `setVmPolicy` is process-wide: a library that
 * installs one changes the behaviour of code it does not own, in an app whose own policy it may be
 * replacing. This watches instead of legislating.
 *
 * Debuggable hosts only. A released app pays nothing: [start] returns immediately.
 */
internal object MainThreadStall {

    /**
     * The gap that counts as a stall.
     *
     * A frame is 16.7 ms at 60 Hz. 700 ms is well past "janky" and well short of the ~5 s that
     * becomes an ANR — the band where the system starts missing lifecycle deadlines, which is the
     * band the report came from. High enough that an emulator drawing a map with software GL does
     * not fill the log.
     */
    const val STALL_MS = 700L

    /** How often to ping. Faster than the deadline, so a stall is noticed near its start. */
    private const val PING_INTERVAL_MS = 200L

    @Volatile private var surface: String = ""
    @Volatile private var watcher: Thread? = null

    private fun debuggable(activity: Activity): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Begin watching, naming the surface that is in front.
     *
     * Idempotent: a second call replaces the name and keeps the single thread, so a paywall
     * presented over an onboarding step does not start two.
     */
    @Synchronized
    fun start(activity: Activity, surfaceName: String) {
        surface = surfaceName
        if (!debuggable(activity)) return
        if (watcher != null) return

        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val answered = AtomicBoolean(false)
                val sentAt = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                try {
                    Thread.sleep(STALL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!answered.get()) {
                    // Still inside something. Sample it WHILE it runs — that is the whole point.
                    val stack = mainThread.stackTrace
                        .take(16)
                        .joinToString("\n    ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                    Log.warning(
                        "main thread BLOCKED for ${SystemClock.uptimeMillis() - sentAt}ms and counting, " +
                            "while \"$surface\" was in front — an activity that cannot pause inside the " +
                            "system's window is reported as wm_failed_to_pause and escalates to an ANR. " +
                            "The main thread is HERE:\n    $stack",
                    )
                    // Wait for it to finish before pinging again, so one stall is one report
                    // rather than one per interval.
                    while (!answered.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(PING_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }
            }
        }, "appdna-main-thread-stall")
        t.isDaemon = true
        watcher = t
        t.start()
    }

    /** Stop watching. Safe to call when nothing is running. */
    @Synchronized
    fun stop() {
        watcher?.interrupt()
        watcher = null
        surface = ""
    }

    /** Test seam: the gap this would report, without needing a looper. */
    internal fun isStall(gapMs: Long): Boolean = gapMs >= STALL_MS
}
