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

    // Through the live decision every tracked event runs (`ScreenManager.triggeredScreen`), on a fresh manager.
    private fun shown(traits: Map<String, Any>): String? = ScreenManager.newForTest().run {
        updateIndex(ScreenIndex(screens = listOf(entry)))
        triggeredScreen("app_open", null, null, ScreenTriggerInputs(sessionCount = 1, daysSinceInstall = 0), traits)
    }

    @Test
    fun aNullTraitDoesNotExist() {
        assertNull(shown(mapOf("name" to JSONObject.NULL, "plan" to "pro")))
    }

    @Test
    fun aTraitWithAValueExists() {
        assertEquals("s1", shown(mapOf("name" to "Ana")))
    }
}
