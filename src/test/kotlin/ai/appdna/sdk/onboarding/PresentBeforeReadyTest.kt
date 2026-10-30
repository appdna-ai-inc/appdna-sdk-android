package ai.appdna.sdk.onboarding

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.config.RemoteConfigManager
import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "ONBOARDING FLOW NOT FOUND" MUST NOT BE SAID BEFORE ANYTHING HAS BEEN LOOKED UP.
 *
 * `present()` returned `false` with that line both when a flow genuinely does not exist and when
 * the SDK had simply not loaded its config yet. A host could not tell the two apart — the return
 * is one boolean — and an integrator reading the log went to their flow id and their console, the
 * one place the problem is not.
 *
 * 🔴 On a COLD FIRST LAUNCH the second window is long. Measured on a Pixel-class emulator with
 * airplane mode on and a fresh install, tapping the host's present button at fixed offsets:
 * `false` at T+20s, `true` at T+30s, nothing restarted. A second run with the network ON: `false`
 * at T+17s, `true` at T+103s on the SAME process. `configure` reaches `loadConfigBundle()` only
 * after a cold Firebase init and a bootstrap round trip, plus up to three retries on a bad
 * network. On every later launch the cached config is already on disk and the window closes
 * before a user can reach a button — which is why this only ever looks like a first-launch bug.
 *
 * The fix is two things, and this covers both: the log tells the truth, and `AppDNA.isReady` lets
 * a host ask which case it is instead of guessing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresentBeforeReadyTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager(): OnboardingFlowManager {
        val storage = LocalStorage(ctx)
        // No config loaded — exactly the cold-first-launch state this is about.
        val rcm = RemoteConfigManager(null, storage, 3600L)
        return OnboardingFlowManager(rcm, EventTracker(IdentityManager(storage), "1.0", "sandbox"))
    }

    private fun presentLogs(flowId: String?): String {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try { manager().present(activity, flowId, null) } finally { System.setOut(original) }
        return buffer.toString()
    }

    @Test
    fun `AppDNA exposes isReady, and it is false before configure`() {
        // The host-facing half: a synchronous answer to "should I retry, or is the flow gone?".
        assertFalse("an unconfigured SDK must not report itself ready", AppDNA.isReady)
    }

    @Test
    fun `presenting before the config loads does NOT claim the flow is missing`() {
        val logs = presentLogs("some-flow-id")
        assertFalse(
            "the SDK has not looked anything up yet, so it must not say 'not found' — logs were:\n$logs",
            logs.contains("Onboarding flow not found"),
        )
        assertTrue(
            "it must say WHY, and point at the thing to wait for — logs were:\n$logs",
            logs.contains("still") && logs.contains("configuring") && logs.contains("onReady"),
        )
        assertTrue("it must name the flow id the host asked for", logs.contains("some-flow-id"))
    }

    @Test
    fun `the no-flowId case is covered too`() {
        // `present(null)` resolves the ACTIVE flow; the same window applies.
        val logs = presentLogs(null)
        assertFalse(logs.contains("Onboarding flow not found"))
        assertTrue(logs.contains("active"))
    }

    @Test
    fun `present still returns false — this changes the explanation, not the contract`() {
        // A host that already handles `false` must keep working unchanged.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(manager().present(activity, "some-flow-id", null))
    }
}
