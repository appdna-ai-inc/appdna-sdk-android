package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.billing.BillingTestPipeline
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A flow the OS kills while it is in the background comes back on the step the user left.
 *
 * The real sequence on a device: the user leaves the app mid-flow, the OS kills the process, the
 * user returns, and Android restores the onboarding activity in a NEW process with only its saved
 * Bundle. The static launch payload and the ViewModel are gone; the host's Application.onCreate has
 * configured the SDK again, which reads its cached config.
 *
 * The test reproduces each part of that: the first activity saves its state and is destroyed (its
 * ViewModelStore is cleared, because it is not a configuration change); the Bundle goes through a
 * Parcel, as it does between processes; the static payload and the completion route are cleared;
 * a NEW config manager is built from the on-disk cache; and a NEW activity instance is created from
 * the Bundle alone.
 *
 * Before the fix, that new activity called finish() — the flow vanished and nobody was told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingProcessDeathResumeTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var events: BillingTestPipeline
    private val delegate = RecordingDelegate()

    private class RecordingDelegate : AppDNAOnboardingDelegate {
        // Synchronized: a step hook's deadline path can report from a background thread.
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override fun onOnboardingStarted(flowId: String) { calls += "started:$flowId" }
        override fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {
            calls += "step:$flowId:$stepId:$stepIndex"
        }
        override fun onOnboardingDismissed(flowId: String, atStep: Int) { calls += "dismissed:$flowId:$atStep" }
        override fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) { calls += "completed:$flowId" }
    }

    private fun step(id: String, title: String) =
        mapOf("id" to id, "type" to "welcome", "config" to mapOf("title" to title))

    private val fourSteps = listOf(step("s1", "One"), step("s2", "Two"), step("s3", "Three"), step("s4", "Four"))

    /** Write [steps] as the cached onboarding config a previous online session left on disk. */
    private fun cacheFlow(steps: List<Map<String, Any>>) {
        LocalStorage(ctx).setString(
            "cache_onboarding",
            JSONObject(mapOf("active_flow_id" to FLOW, "flows" to mapOf(FLOW to mapOf(
                "id" to FLOW, "name" to "Resume probe", "version" to 3, "steps" to steps,
            )))).toString(),
        )
    }

    /** What configure() builds in a fresh process: a config manager that reads the cache from disk. */
    private fun freshProcessManager(): OnboardingFlowManager =
        OnboardingFlowManager(RemoteConfigManager(null, LocalStorage(ctx), 3600L), events.tracker)

    @Before
    fun setUp() {
        events = BillingTestPipeline(ctx, "onboarding_resume")
        cacheFlow(fourSteps)
        AppDNA.onboarding.setDelegate(delegate)
    }

    /**
     * Every activity this test creates is destroyed in [tearDown]: Robolectric does not destroy a
     * finished activity on its own, and one left resumed keeps its composition alive into the
     * Compose tests that run after this class in the same JVM.
     */
    private val controllers = mutableListOf<ActivityController<*>>()

    private fun <T : Activity> track(c: ActivityController<T>): ActivityController<T> = c.also { controllers += it }

    @After
    fun tearDown() {
        for (c in controllers.asReversed()) {
            if (!c.get().isDestroyed) runCatching { c.pause().stop().destroy() }
        }
        controllers.clear()
        idle()
        AppDNA.installOnboardingForTest(null, ready = false)
        AppDNA.onboarding.setDelegate(null)
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        LocalStorage(ctx).setString("cache_onboarding", "{}")
        events.close()
    }

    private fun vmOf(activity: OnboardingActivity): OnboardingViewModel =
        ViewModelProvider(activity)[OnboardingViewModel::class.java]

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Present the flow in the "first process", move the user to step 3 with answers on steps 1-2,
     * then kill it: save, destroy, parcel the Bundle, drop every static. Returns the Bundle the
     * system would hand the restored activity.
     */
    private fun presentAdvanceAndKill(): Bundle {
        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val host = track(Robolectric.buildActivity(Activity::class.java).setup()).get()
        assertTrue(AppDNA.presentOnboarding(host, FLOW))
        val first = track(Robolectric.buildActivity(OnboardingActivity::class.java).setup())
        idle()
        val vm = vmOf(first.get())
        assertTrue("the first launch must bind its payload", vm.isBound)
        vm.responses["s1"] = mapOf("selected" to listOf("opt_a", "opt_b"))
        vm.responses["s2"] = mapOf("name" to "typed text")
        vm.navigationHistory.addAll(listOf("s1", "s2"))
        vm.currentIndex.intValue = 2
        PendingCompletionRoute.record("https://example.com/after")
        idle()

        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()

        // Across processes the Bundle is parcelled: anything that cannot survive that fails here.
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            parcel.readBundle(OnboardingActivity::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }

        // The process dies: statics and the SDK's managers go with it.
        OnboardingActivity.clearActiveLaunches()
        PendingCompletionRoute.clear()
        AppDNA.installOnboardingForTest(null, ready = false)
        return restored
    }

    private fun restore(saved: Bundle): ActivityController<OnboardingActivity> {
        val controller = track(Robolectric.buildActivity(OnboardingActivity::class.java).setup(saved))
        idle()
        return controller
    }

    private fun stepViewedIndexes(): List<Int> = events.events()
        .filter { it.first == "onboarding_step_viewed" && it.second.optString("flow_id") == FLOW }
        .map { it.second.getInt("step_index") }

    @Test
    fun `a flow killed in the background comes back on the step the user left, with their answers`() {
        val saved = presentAdvanceAndKill()
        val viewedBeforeKill = stepViewedIndexes()

        // The new process: Application.onCreate configured the SDK, which read its cached config.
        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val restored = restore(saved)
        val activity = restored.get()

        assertFalse("the restored activity finished instead of resuming the flow", activity.isFinishing)
        val vm = vmOf(activity)
        assertTrue("the restored activity never bound a flow", vm.isBound)
        assertEquals(FLOW, vm.flow?.id)
        assertEquals("the user must be back on step 3", 2, vm.currentIndex.intValue)
        assertEquals(mapOf("selected" to listOf("opt_a", "opt_b")), vm.responses["s1"])
        assertEquals(mapOf("name" to "typed text"), vm.responses["s2"])
        assertEquals(listOf("s1", "s2"), vm.navigationHistory.toList())
        assertEquals("a CTA's completion route must survive too", "https://example.com/after", PendingCompletionRoute.peek())

        // The flow continued; it did not start again.
        assertEquals(1, events.count("onboarding_flow_started"))
        assertEquals(listOf("started:$FLOW"), delegate.calls.filter { it.startsWith("started") })

        // The rebuilt closures still carry the flow id and reach the registered delegate: the step on
        // screen reports itself viewed in the new process, and reports step 3, not step 1.
        val viewedAfter = stepViewedIndexes().drop(viewedBeforeKill.size)
        assertEquals("the resumed step must be reported viewed, once, as step 3", listOf(2), viewedAfter)
        assertTrue("the registered delegate must hear step 3 — calls: ${delegate.calls}", delegate.calls.contains("step:$FLOW:s3:2"))
        assertFalse("nothing may report the flow dismissed", delegate.calls.any { it.startsWith("dismissed") })

    }

    @Test
    fun `a delegate the host registers after the flow is back still hears how it ends`() {
        val saved = presentAdvanceAndKill()

        // In the new process the host registers its delegate from a screen created after this one.
        AppDNA.onboarding.setDelegate(null)
        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val restored = restore(saved)
        assertTrue(vmOf(restored.get()).isBound)

        val late = RecordingDelegate()
        AppDNA.onboarding.setDelegate(late)
        @Suppress("DEPRECATION")
        restored.get().onBackPressed()

        assertTrue("the dismissal must reach the delegate registered now", late.calls.contains("dismissed:$FLOW:2"))
        assertTrue(restored.get().isFinishing)
    }

    @Test
    fun `a restore that arrives before the SDK is configured waits for it, then resumes`() {
        val saved = presentAdvanceAndKill()

        // configure() was called (Application.onCreate) but its onboarding manager is not built yet.
        AppDNA.installOnboardingForTest(null, ready = false, configuring = true)
        val restored = restore(saved)
        assertFalse("it must wait, not finish", restored.get().isFinishing)
        assertFalse(vmOf(restored.get()).isBound)

        // A second save while waiting hands the same state on — no step reset to 0.
        val again = Bundle()
        restored.saveInstanceState(again)
        assertEquals(FLOW, again.getString("appdna_onboarding_flow_id"))
        assertEquals(2, again.getInt("appdna_onboarding_current_step_index", -1))

        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        idle()
        val vm = vmOf(restored.get())
        assertTrue("onReady must complete the resume", vm.isBound)
        assertEquals(2, vm.currentIndex.intValue)
        assertFalse(restored.get().isFinishing)
    }

    @Test
    fun `an SDK that never becomes ready ends the flow as dismissed, not silently`() {
        val saved = presentAdvanceAndKill()
        // configure() was called, and its bootstrap never finishes.
        AppDNA.installOnboardingForTest(null, ready = false, configuring = true)
        val restored = restore(saved)
        assertFalse(restored.get().isFinishing)

        // Well past the activity's bound on the wait.
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)

        assertTrue("the bounded wait must end the activity", restored.get().isFinishing)
        assertTrue("the host must be told", delegate.calls.contains("dismissed:$FLOW:2"))
        val dismissed = events.events().lastOrNull { it.first == "onboarding_flow_dismissed" }
        assertNotNull("the dismissal must be tracked", dismissed)
        assertEquals(FLOW, dismissed!!.second.getString("flow_id"))
        assertEquals("s3", dismissed.second.getString("last_step_id"))
        assertEquals(2, dismissed.second.getInt("last_step_index"))
    }

    @Test
    fun `a host that never configured the SDK in Application onCreate ends the flow at once, without waiting`() {
        val saved = presentAdvanceAndKill()
        // The new process: Application.onCreate ran and did not call configure() — a cross-platform
        // app configures from Dart or JavaScript, which has not run yet. So the SDK has no tracker
        // either: what it tracks now is buffered until a later configure().
        events.close()
        AppDNA.drainPreInitBufferForTest()
        try {
            assertConfigureNeverCalledEndsAtOnce(saved)
        } finally {
            AppDNA.drainPreInitBufferForTest()
            events = BillingTestPipeline(ctx, "onboarding_resume")
        }
    }

    private fun assertConfigureNeverCalledEndsAtOnce(saved: Bundle) {
        val restored = track(Robolectric.buildActivity(OnboardingActivity::class.java).setup(saved))

        // At once: no main-looper idling, no virtual time — the 15s wait never starts.
        assertTrue("an unconfigured SDK must not be waited for", restored.get().isFinishing)
        assertFalse(vmOf(restored.get()).isBound)
        assertTrue("a delegate registered before configure() still hears it", delegate.calls.contains("dismissed:$FLOW:2"))
        val buffered = AppDNA.drainPreInitBufferForTest().filter { it.first == "onboarding_flow_dismissed" }
        assertEquals("the dismissal is held for a later configure()", 1, buffered.size)
        assertEquals(mapOf("flow_id" to FLOW, "last_step_id" to "s3", "last_step_index" to 2), buffered.single().second)
    }

    @Test
    fun `a flow unpublished while the app was closed ends as dismissed`() {
        val saved = presentAdvanceAndKill()
        LocalStorage(ctx).setString("cache_onboarding", JSONObject(mapOf("flows" to emptyMap<String, Any>())).toString())

        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val restored = restore(saved)

        assertTrue(restored.get().isFinishing)
        assertTrue(delegate.calls.contains("dismissed:$FLOW:2"))
        assertEquals(1, events.count("onboarding_flow_dismissed"))
    }

    @Test
    fun `a republished flow resumes on the same step where it moved, and ends where it is gone`() {
        val saved = presentAdvanceAndKill()

        // A step was inserted before the one the user was on: s3 is now at index 3.
        cacheFlow(listOf(fourSteps[0], step("s1b", "New"), fourSteps[1], fourSteps[2], fourSteps[3]))
        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val moved = restore(Bundle(saved))
        assertFalse(moved.get().isFinishing)
        assertEquals("the user must land on s3 where it now is", 3, vmOf(moved.get()).currentIndex.intValue)
        moved.pause().stop().destroy()

        // The step the user was on was deleted: putting them on an unrelated screen is wrong.
        cacheFlow(listOf(fourSteps[0], fourSteps[1], fourSteps[3]))
        AppDNA.installOnboardingForTest(freshProcessManager(), ready = true)
        val gone = restore(Bundle(saved))
        assertTrue(gone.get().isFinishing)
        assertTrue(delegate.calls.contains("dismissed:$FLOW:2"))
    }

    @Test
    fun `an activity started with no payload and no saved flow still finishes`() {
        // The pre-existing guard: nothing to show and nothing to resume.
        val controller = track(Robolectric.buildActivity(OnboardingActivity::class.java).setup())
        assertTrue(controller.get().isFinishing)
        assertTrue(delegate.calls.isEmpty())
    }

    private companion object {
        const val FLOW = "resume_probe_flow"
    }
}
