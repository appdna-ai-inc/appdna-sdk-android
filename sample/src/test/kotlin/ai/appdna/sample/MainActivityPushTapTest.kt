package ai.appdna.sample

import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sample hands a tapped background push to `AppDNA.handlePushTap` in onCreate and in onNewIntent, as
 * the push guide tells integrators to. The SDK makes a handled tap INERT (it removes the AppDNA marker
 * extra), so the marker's absence afterwards proves the call happened. THE CONTROL: an intent that is not
 * an AppDNA push keeps its extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPushTapTest {
    private fun pushIntent(id: String) = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
        putExtra("appdna", "1"); putExtra("push_id", id); putExtra("delivery_id", "d-$id")
        putExtra("action_type", "deep_link"); putExtra("action_value", "appdnasample://demo/walk")
    }

    @Test
    fun `a push tap is handed to the SDK on cold start and on a new intent`() {
        val other = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply { putExtra("appdna_other", "keep") }
        val controlActivity = Robolectric.buildActivity(MainActivity::class.java, other).setup().get()
        assertEquals("control: a non-AppDNA intent keeps its extras", "keep", controlActivity.intent.getStringExtra("appdna_other"))

        val controller = Robolectric.buildActivity(MainActivity::class.java, pushIntent("cold")).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("onCreate did not hand the push tap to the SDK", controller.get().intent.getStringExtra("appdna"))

        val warm = pushIntent("warm")
        controller.newIntent(warm)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("onNewIntent did not hand the push tap to the SDK", warm.getStringExtra("appdna"))
    }
}
