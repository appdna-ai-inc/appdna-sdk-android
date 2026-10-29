package ai.appdna.sdk

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.TestLifecycleApplication
import java.lang.reflect.Method

/**
 * The Application every Robolectric test in this module runs under (`robolectric.properties`).
 *
 * It exists for one hazard: **a Compose dispatch still waiting on the main looper when a test ends
 * breaks every Compose test after it in the same JVM.**
 *
 * `AndroidUiDispatcher.Main` is a process-wide singleton. To run a coroutine it sets a private
 * "already scheduled" flag and posts ONE message to the main looper (plus one Choreographer frame
 * callback); later dispatches only join the queue, trusting that message to drain it. Between tests
 * Robolectric clears the main looper's queue and replaces the thread's Choreographer — so if a test
 * ends with that message unrun, it is discarded, the flag stays set, and from then on nothing
 * dispatched to the main Compose dispatcher ever runs again:
 *
 *   - `GlobalSnapshotManager` stops sending apply notifications, so every Compose-rule test that
 *     writes state outside composition waits on "unapplied snapshot changes" and fails after 60 s
 *     with `AppNotIdleException: Compose did not get idle`;
 *   - an activity's `LaunchedEffect`s never start, so an onboarding step is never reported viewed.
 *
 * Measured on #1170: one test that does nothing but `CoroutineScope(AndroidUiDispatcher.Main).launch {}`
 * and returns made `CompletionRouteCtaDispatchTest` fail 4 of 12 at its `setContent` (60 s each) and
 * `OnboardingProcessDeathResumeTest` fail "the resumed step must be reported viewed, once, as step 3
 * expected:<[2]> but was:<[]>" — the exact failures of a 69-failure full run that was
 * attributed to the process-death class. Run ahead of the whole suite, the same one test reproduced
 * that run exactly: the same 69 failures in the same 17 classes, in 71 minutes instead of 3.
 *
 * Who leaves such a dispatch behind is any test whose last Compose work is posted after its last
 * looper idle — a destroy, a state write, or a background thread (an IO `withContext`, a `delay`
 * on the real-time executor) resuming onto the main dispatcher while a test runs that never idles
 * the looper. The last kind depends on timing, which is how a suite goes red on a loaded machine
 * and green on the next run.
 *
 * So after every test, before Robolectric's reset, a pending Compose dispatch is RUN rather than
 * discarded — what a real looper would have done. If it cannot be run, the test that left it fails
 * here, by name, instead of sixty Compose tests timing out an hour later with nothing pointing back.
 *
 * Reading the flag needs reflection on Compose internals. If a Compose upgrade renames them, every
 * test fails here naming the missing field rather than the guard going quiet; and
 * `ComposeDispatcherGuardTest` proves, with a control, that the guard sees a pending dispatch and
 * that the test after one still runs Compose work.
 */
class RobolectricTestApplication : Application(), TestLifecycleApplication {

    override fun beforeTest(method: Method) {}

    override fun prepareTest(test: Any) {}

    override fun afterTest(method: Method) {
        val dispatcher = ComposeMainDispatcher.ifStarted() ?: return
        if (!dispatcher.hasPendingDispatch()) return
        shadowOf(Looper.getMainLooper()).idle()
        check(!dispatcher.hasPendingDispatch()) {
            "${method.declaringClass.simpleName}.${method.name} ended with a Compose dispatch that the " +
                "main looper cannot run. Robolectric discards it at reset and every later Compose test " +
                "in this JVM then times out (AppNotIdleException). See RobolectricTestApplication."
        }
    }
}

/** Read-only view of `AndroidUiDispatcher.Main`'s scheduling state. */
internal class ComposeMainDispatcher private constructor(private val dispatcher: Any) {

    /** True while a dispatch is scheduled and its main-looper message has not run yet. */
    fun hasPendingDispatch(): Boolean = TRAMPOLINE.getBoolean(dispatcher)

    companion object {
        private val CLASS: Class<*> = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher")
        private val MAIN: Lazy<*> = CLASS.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
        private val TRAMPOLINE = CLASS.getDeclaredField("scheduledTrampolineDispatch").apply { isAccessible = true }

        /** The dispatcher, or null when no Compose code has started it in this JVM (nothing to guard). */
        fun ifStarted(): ComposeMainDispatcher? {
            if (!MAIN.isInitialized()) return null
            val context = MAIN.value as kotlin.coroutines.CoroutineContext
            return ComposeMainDispatcher(context[kotlin.coroutines.ContinuationInterceptor]!!)
        }
    }
}
