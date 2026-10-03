package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `emitted_by` marker survives SDK billing emits and never host events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingEventMarkerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var pipe: BillingTestPipeline? = null

    @After fun tearDown() { pipe?.close() }

    @Test fun `a billing emit reaches the tracker with emitted_by sdk`() {
        pipe = BillingTestPipeline(ctx, "marker")
        AppDNA.trackBillingEvent("purchase_completed", mapOf("product_id" to "p1"))
        assertEquals("sdk", pipe!!.props("purchase_completed").getString("emitted_by"))
    }

    @Test fun `the public track strips emitted_by and _appdna_origin`() {
        pipe = BillingTestPipeline(ctx, "strip")
        AppDNA.track("purchase_completed", mapOf("product_id" to "p1", "emitted_by" to "sdk", "_appdna_origin" to "integration:x"))
        val props = pipe!!.props("purchase_completed")
        assertEquals("p1", props.getString("product_id"))
        assertFalse(props.has("emitted_by"))
        assertFalse(props.has("_appdna_origin"))
    }

    @Test fun `a host event tracked before configure is stripped too (buffered, then flushed)`() {
        AppDNA.installEventTrackerForTest(null)
        AppDNA.track("purchase_completed", mapOf("product_id" to "p1", "emitted_by" to "sdk"))
        val buffer = AppDNA::class.java.getDeclaredField("preInitBuffer").apply { isAccessible = true }.get(AppDNA) as java.util.Collection<*>
        val entry = buffer.last()!!
        val props = entry.javaClass.getDeclaredField("properties").apply { isAccessible = true }.get(entry) as Map<*, *>
        assertFalse(props.containsKey("emitted_by"))
        buffer.clear()
    }
}
