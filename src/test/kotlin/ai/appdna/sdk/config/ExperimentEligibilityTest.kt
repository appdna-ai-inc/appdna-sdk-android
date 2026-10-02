package ai.appdna.sdk.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experiment audience gate: traffic allocation + the targeting rules the Console offers, evaluated on device before a
 * variant is assigned. The shared fixtures (`sdk-shared-fixtures/experiment_serving/`, `get_variant`, driven by
 * SharedFixtureTest) pin the cross-platform answers; these tests pin the edges and the distributions. Mirrors iOS
 * `ExperimentEligibilityTests.swift`.
 */
class ExperimentEligibilityTest {

    private fun ctx(app: String? = "3.0.0", region: String? = "US", installed: Long? = 2_000L, traits: Map<String, Any> = emptyMap()) =
        ExperimentEligibilityContext("android", app, region, installed, traits)

    private fun t(map: Map<String, Any?>) = ExperimentTargeting.fromAny(map)

    @Test
    fun `isAllocated edges`() {
        assertTrue("legacy doc = everyone", ExperimentEligibility.isAllocated("e", "s", "u", null))
        assertTrue(ExperimentEligibility.isAllocated("e", "s", "u", 1.0))
        assertTrue(ExperimentEligibility.isAllocated("e", "s", "u", 7.0))
        assertFalse(ExperimentEligibility.isAllocated("e", "s", "u", 0.0))
        assertFalse(ExperimentEligibility.isAllocated("e", "s", "u", -1.0))
        assertFalse(ExperimentEligibility.isAllocated("e", "s", "u", Double.NaN))
        assertFalse(ExperimentEligibility.isAllocated("e", "s", "u", Double.POSITIVE_INFINITY))
    }

    @Test
    fun `allocation share, independence from the variant split, and growth stability`() {
        val in30 = mutableSetOf<String>()
        val in60 = mutableSetOf<String>()
        var controlIn30 = 0
        val variants = listOf(ExperimentVariant("control", 0.5), ExperimentVariant("b", 0.5))
        for (i in 0 until 10_000) {
            val u = "user_$i"
            if (ExperimentEligibility.isAllocated("exp", "salt", u, 0.3)) {
                in30 += u
                if (ExperimentBucketer.assignVariant("exp", u, "salt", variants) == "control") controlIn30++
            }
            if (ExperimentEligibility.isAllocated("exp", "salt", u, 0.6)) in60 += u
        }
        assertEquals(0.3, in30.size / 10_000.0, 0.02)
        assertEquals(0.6, in60.size / 10_000.0, 0.02)
        assertTrue("growing the allocation must keep every user who was in", in60.containsAll(in30))
        assertEquals(0.5, controlIn30.toDouble() / in30.size, 0.03)
    }

    @Test
    fun `compareVersions`() {
        assertEquals(-1, Integer.signum(ExperimentEligibility.compareVersions("2.9.3", "2.10")!!))
        assertEquals(0, ExperimentEligibility.compareVersions("2.10.0-beta", "2.10"))
        assertEquals(1, Integer.signum(ExperimentEligibility.compareVersions("10", "9.9.9")!!))
        assertEquals(0, ExperimentEligibility.compareVersions(" 1.2 ", "1.2.0.0"))
        assertEquals(1, Integer.signum(ExperimentEligibility.compareVersions("1.2.1", "1.2")!!))
        assertNull(ExperimentEligibility.compareVersions("beta", "1.0"))
        assertNull(ExperimentEligibility.compareVersions("1.0", ""))
    }

    @Test
    fun `each rule`() {
        val E = ExperimentEligibility
        assertNull(E.failedRule(null, null, ctx()))
        assertNull(E.failedRule(t(emptyMap()), null, ctx()))
        assertNull("empty list = every country", E.failedRule(t(mapOf("countries" to emptyList<String>())), null, ctx(region = null)))
        assertEquals(ExperimentEligibility.Ineligible.COUNTRY, E.failedRule(t(mapOf("countries" to listOf("DE"))), null, ctx()))
        assertNull(E.failedRule(t(mapOf("countries" to listOf(" de ", "us"))), null, ctx(region = "us")))
        assertEquals(ExperimentEligibility.Ineligible.MIN_APP_VERSION, E.failedRule(t(mapOf("min_app_version" to "3.0.1")), null, ctx()))
        assertNull(E.failedRule(t(mapOf("min_app_version" to "3")), null, ctx()))
        assertNull("blank = no rule", E.failedRule(t(mapOf("min_app_version" to "  ")), null, ctx(app = null)))
        assertEquals(ExperimentEligibility.Ineligible.NEW_USERS_ONLY, E.failedRule(t(mapOf("new_users_only" to true)), 2_001L, ctx()))
        assertNull(E.failedRule(t(mapOf("new_users_only" to true)), 2_000L, ctx()))
        assertNull(E.failedRule(t(mapOf("new_users_only" to false)), null, ctx(installed = null)))
        assertEquals(ExperimentEligibility.Ineligible.NEW_USERS_ONLY, E.failedRule(t(mapOf("new_users_only" to true)), 1L, ctx(installed = null)))
        val traits = t(mapOf("user_traits" to listOf(
            mapOf("trait" to "plan", "operator" to "in", "value" to listOf("pro", "team")),
            mapOf("trait" to "beta", "operator" to "exists"),
        )))
        assertNull(E.failedRule(traits, null, ctx(traits = mapOf("plan" to "pro", "beta" to true))))
        assertEquals(ExperimentEligibility.Ineligible.USER_TRAITS, E.failedRule(traits, null, ctx(traits = mapOf("plan" to "pro"))))
    }

    @Test
    fun `malformed targeting fails closed`() {
        val bad = ExperimentTargeting.fromAny(mapOf("countries" to "US"))!!
        assertTrue(bad.malformed)
        assertEquals(ExperimentEligibility.Ineligible.MALFORMED_TARGETING, ExperimentEligibility.failedRule(bad, null, ctx()))
        assertTrue(ExperimentTargeting.fromAny("not an object")!!.malformed)
        assertTrue(ExperimentTargeting.fromAny(mapOf("user_traits" to listOf("x")))!!.malformed)
        assertNull(ExperimentTargeting.fromAny(null))
        // (the served doc's nested targeting / traffic_allocation / started_at_ms through the REAL RemoteConfigManager
        // parse is covered by the get_variant fixtures in SharedFixtureTest)
    }
}
