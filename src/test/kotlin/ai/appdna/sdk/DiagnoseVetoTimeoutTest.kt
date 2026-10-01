package ai.appdna.sdk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `diagnose()` reports the veto wait as the host gave it. NEGATIVE CONTROL: it printed the whole-second
 * `vetoTimeout`, which a wrapper rounds UP, so a 0.5 s wait read "1".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnoseVetoTimeoutTest {
    @Test
    fun `the exact seconds are reported as given`() {
        assertEquals("0.5", AppDNA.vetoTimeoutForDiagnose(AppDNAOptions(vetoTimeout = 1L).apply { vetoTimeoutSeconds = 0.5 }))
        assertEquals("2.7", AppDNA.vetoTimeoutForDiagnose(AppDNAOptions(vetoTimeout = 3L).apply { vetoTimeoutSeconds = 2.7 }))
        assertEquals("8", AppDNA.vetoTimeoutForDiagnose(AppDNAOptions(vetoTimeout = 8L).apply { vetoTimeoutSeconds = 8.0 }))
        assertEquals("5", AppDNA.vetoTimeoutForDiagnose(AppDNAOptions()))
        assertEquals("7", AppDNA.vetoTimeoutForDiagnose(AppDNAOptions(vetoTimeout = 7L)))
    }
}
