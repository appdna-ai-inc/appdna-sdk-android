package ai.appdna.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `java.time` is API 26 and minSdk is 24: below API 26 [IsoInstant] parses with `SimpleDateFormat`.
 * Both paths must agree on the instant (the callers used to fall back to a zone-less format on
 * Android 7, which read the time in the device's zone). The API 24 path is the one lint `NewApi`
 * forced; the JVM has `java.time`, so only `SDK_INT` selects it here.
 */
@RunWith(RobolectricTestRunner::class)
class IsoInstantTest {

    private val cases = mapOf(
        "2026-05-07T10:00:00Z" to 1778148000000L,
        "2026-05-07T10:00:00.250Z" to 1778148000250L,
        "2026-05-07T12:00:00+02:00" to 1778148000000L,
        "2026-05-07T12:00:00.250+02:00" to 1778148000250L,
        "2026-05-07T10:00:00.250123Z" to 1778148000250L,
    )

    private fun assertCases() {
        for ((iso, ms) in cases) assertEquals(iso, ms, IsoInstant.parseEpochMs(iso))
        assertNull(IsoInstant.parseEpochMs("2026-05-07"))
        assertNull(IsoInstant.parseEpochMs("2026-05-07T10:00:00"))
        assertNull(IsoInstant.parseEpochMs("not a date"))
        assertNull(IsoInstant.parseEpochMs(""))
    }

    @Test @Config(sdk = [24]) fun `api 24 - SimpleDateFormat path`() = assertCases()

    @Test @Config(sdk = [33]) fun `api 33 - java time path`() = assertCases()
}
