package ai.appdna.sdk.screens

import ai.appdna.sdk.core.TraitCondition
import ai.appdna.sdk.core.UnifiedTriggerRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A screen's user-trait trigger reads "exists" as a non-null value, as the audience rules do (the shared
 * fixture `exists_requires_non_null_value`). A trait identify stored as null (`JSONObject.NULL`) does not
 * exist; the same trait with a value does.
 */
class TraitExistsNonNullTriggerTest {

    private val entry = ScreenIndexEntry(
        id = "s1",
        name = "Welcome back",
        triggerRules = UnifiedTriggerRules(userTraits = listOf(TraitCondition(trait = "name", operator = "exists"))),
    )

    private fun shown(traits: Map<String, Any>): String? = AutoTriggerEngine().evaluate(
        entries = listOf(entry), event = "app_open", properties = null, userTraits = traits,
        sessionCount = 1, daysSinceInstall = 0, currentScreenName = null,
    )

    @Test
    fun aNullTraitDoesNotExist() {
        assertNull(shown(mapOf("name" to JSONObject.NULL, "plan" to "pro")))
    }

    @Test
    fun aTraitWithAValueExists() {
        assertEquals("s1", shown(mapOf("name" to "Ana")))
    }
}
