package ai.appdna.sdk.core

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.Choreographer
import ai.appdna.sdk.Log

/**
 * #952 — NAME THE CALL THAT HELD THE MAIN THREAD, instead of learning afterwards that something did.
 *
 * A customer's logcat carried two lines from the system and nothing from us:
 *
 *     W/ActivityTaskManager  Activity top resumed state loss timeout for … PaywallActivity
 *     I/wm_failed_to_pause   [0,…,…/ai.appdna.sdk.paywalls.PaywallActivity,(none)]
 *
 * Both mean the same thing: `onPause` did not return inside the window the system allows, so the
 * main thread was busy when it should have been free. An activity that cannot pause is the shape
 * the system escalates — a dropped frame budget first, then an ANR, then a kill — and the report
 * it was found under was "the app closes after a short moment".
 *
 * 🔴 WHAT THE LOG COULD NOT SAY, AND WHY THIS EXISTS. It records that a deadline was missed. It
 * does not record what was running. Every candidate had to be ruled out by reading
 * (`PaywallActivity` overrides neither `onPause` nor `onStop` and contains no `runBlocking`), and
 * reading is how a main-thread stall gets written in the first place. Nothing in the SDK measured
 * this, so a second sighting would have been exactly as uninformative as the first.
 *
 * 🔴 NOT StrictMode, which was the first idea. `StrictMode.setVmPolicy` is process-wide: a library
 * that installs one changes the behaviour of code it does not own, on a thread it does not own,
 * in an app whose own policy it may be replacing. This watches instead of legislating — a
 * `Choreographer` callback that notices when the gap between two frames is far longer than a frame
 * should be, while one of OUR surfaces is in front.
 *
 * It runs only in a DEBUGGABLE host. A released app pays nothing: [start] returns immediately.
 */
internal object MainThreadStall {

    /**
     * The gap that counts as a stall.
     *
     * A frame is 16.7ms at 60Hz. 700ms is well past "janky" and well short of the ~5s that becomes
     * an ANR — it is the band where the system starts missing lifecycle deadlines, which is the
     * band the report came from. Low enough to catch the cause, high enough that an emulator
     * drawing a map with software GL does not fill the log.
     */
    const val STALL_MS = 700L

    private var callback: Choreographer.FrameCallback? = null
    private var surface: String = ""
    private var lastFrameMs = 0L

    /** Whether the host is debuggable — a release build never measures anything. */
    private fun debuggable(activity: Activity): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Begin watching, naming the surface that is in front.
     *
     * Idempotent: a second call replaces the name and keeps the single callback, so a paywall
     * presented over an onboarding step does not install two.
     */
    fun start(activity: Activity, surfaceName: String) {
        surface = surfaceName
        if (!debuggable(activity)) return
        if (callback != null) return

        lastFrameMs = SystemClock.uptimeMillis()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = SystemClock.uptimeMillis()
                val gap = now - lastFrameMs
                lastFrameMs = now
                if (gap >= STALL_MS) {
                    /*
                     * The stack is the point. A gap on its own is what the system already told us;
                     * what was missing is WHICH call held the thread, and the main thread's stack
                     * at the first frame after the gap is the closest thing available without a
                     * profiler attached.
                     */
                    val stack = Thread.currentThread().stackTrace
                        .drop(3)
                        .take(12)
                        .joinToString("\n    ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                    Log.warning(
                        "main thread stalled ${gap}ms while \"$surface\" was in front — " +
                            "an activity that cannot pause inside the system's window is reported as " +
                            "wm_failed_to_pause and escalates to an ANR. Stack at the first frame after:\n    $stack",
                    )
                }
                if (callback === this) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        callback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** Stop watching. Safe to call when nothing is running. */
    fun stop() {
        callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        callback = null
        surface = ""
    }

    /** Test seam: the gap this would report, without needing a real Choreographer. */
    internal fun isStall(gapMs: Long): Boolean = gapMs >= STALL_MS
}
