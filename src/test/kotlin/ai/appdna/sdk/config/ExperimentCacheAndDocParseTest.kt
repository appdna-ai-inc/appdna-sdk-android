package ai.appdna.sdk.config

import ai.appdna.sdk.IdentityManager
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The served experiment doc through the REAL [RemoteConfigManager] — from the disk cache on a cold start, and with
 * malformed fields — so Android answers what iOS answers (`ExperimentEligibilityTests`).
 *
 * NEGATIVE CONTROLS (base code):
 *  - `cachedExperimentsLoadOnAColdStart…`: the cache stored the doc as JSON text and the loader handed the parser
 *    JSONObject values, which it skips → `getExperimentConfig("exp_alloc_1")` was null (first assertion fails), so
 *    an offline cold start had no experiments.
 *  - `aMalformedTrafficAllocationAllocatesNoOne`: a string allocation read as absent → `trafficAllocation` null and
 *    the user was allocated (the `isNaN` and `assertNull(getVariant)` assertions fail).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperimentCacheAndDocParseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun experimentDoc(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "id" to "exp_alloc_1",
        "status" to "running",
        "salt" to "salt_r33",
        "platforms" to listOf("ios", "android"),
        "variants" to listOf(
            mapOf("id" to "control", "weight" to 0.5, "is_control" to true),
            mapOf("id" to "b", "weight" to 0.5, "is_control" to false, "payload" to mapOf("color" to "red")),
        ),
        "traffic_allocation" to 1,
        "started_at_ms" to 1_767_225_600_000L,
        "targeting" to mapOf("countries" to listOf("US"), "user_traits" to listOf(mapOf("trait" to "plan", "operator" to "eq", "value" to "pro"))),
    ) + extra

    private fun manager(rcm: RemoteConfigManager, storage: LocalStorage): ExperimentManager {
        val identity = IdentityManager(storage)
        identity.identify("user_4", mapOf("plan" to "pro"))
        return ExperimentManager(rcm, identity, EventTracker(identity, "1.0", "sandbox")) { traits ->
            ExperimentEligibilityContext("android", "3.2.0", "US", 1_767_312_000_000L, traits)
        }
    }

    @Test
    fun cachedExperimentsLoadOnAColdStartWithVariantsTargetingAllocationAndStart() {
        val storage = LocalStorage(ctx)
        // What a successful fetch writes: `cacheData("experiments", JSONObject(snapshot.data).toString())`.
        storage.setString("cache_experiments", JSONObject(mapOf("experiments" to mapOf("exp_alloc_1" to experimentDoc()))).toString())

        // A new process, offline: the manager is built from the cache alone.
        val rcm = RemoteConfigManager(null, storage, 3600L)
        val cfg = rcm.getExperimentConfig("exp_alloc_1")
        assertNotNull("the cached experiment did not load", cfg)
        cfg!!
        assertEquals(listOf("control", "b"), cfg.variants.map { it.id })
        assertEquals(mapOf("color" to "red"), cfg.variants[1].config)
        assertEquals(1.0, cfg.trafficAllocation!!, 0.0)
        assertEquals(1_767_225_600_000L, cfg.startedAtMs)
        assertEquals(listOf("US"), cfg.targeting?.countries)
        assertEquals("plan", cfg.targeting?.userTraits?.single()?.trait)
        assertFalse(cfg.targeting!!.malformed)

        assertEquals("b", manager(rcm, storage).getVariant("exp_alloc_1"))
    }

    @Test
    fun aMalformedTrafficAllocationAllocatesNoOne() {
        val storage = LocalStorage(ctx)
        for (bad in listOf<Any>("1", true, mapOf("v" to 1))) {
            val rcm = RemoteConfigManager(null, storage, 3600L)
            rcm.loadBundledConfig(mapOf("experiments" to mapOf("exp_alloc_1" to experimentDoc(mapOf("traffic_allocation" to bad)).filterValues { it != null })))
            val cfg = rcm.getExperimentConfig("exp_alloc_1")!!
            assertTrue("traffic_allocation=$bad must read as malformed (NaN)", cfg.trafficAllocation!!.isNaN())
            assertNull("traffic_allocation=$bad must allocate no one", manager(rcm, storage).getVariant("exp_alloc_1"))
        }
    }

    @Test
    fun aMalformedStartReadsAsAbsentAndAFractionalOneIsTruncated() {
        val storage = LocalStorage(ctx)
        val rcm = RemoteConfigManager(null, storage, 3600L)
        rcm.loadBundledConfig(mapOf("experiments" to mapOf(
            "s_str" to experimentDoc(mapOf("started_at_ms" to "2026-01-01")),
            "s_frac" to experimentDoc(mapOf("started_at_ms" to 1_767_225_600_000.9)),
        )))
        assertNull(rcm.getExperimentConfig("s_str")!!.startedAtMs)
        assertEquals(1_767_225_600_000L, rcm.getExperimentConfig("s_frac")!!.startedAtMs)
    }
}
