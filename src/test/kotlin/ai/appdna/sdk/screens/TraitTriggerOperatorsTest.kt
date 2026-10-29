package ai.appdna.sdk.screens

import ai.appdna.sdk.core.TraitCondition
import ai.appdna.sdk.core.UnifiedTriggerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A screen's user-trait trigger evaluates its operator the way an audience rule does: an operator the SDK
 * does not implement does NOT fire (it used to fire for every user), `not_exists` is the negation of
 * `exists`, and the comparison operators the console offers are evaluated rather than passed.
 */
class TraitTriggerOperatorsTest {

    // Through the live decision every tracked event runs (`ScreenManager.triggeredScreen`), on a fresh manager.
    private fun shown(operator: String, value: Any?, traits: Map<String, Any>): String? =
        ScreenManager.newForTest().run {
            updateIndex(ScreenIndex(screens = listOf(
                ScreenIndexEntry(
                    id = "s1", name = "Offer",
                    triggerRules = UnifiedTriggerRules(userTraits = listOf(TraitCondition("plan", operator, value))),
                ),
            )))
            triggeredScreen("app_open", null, null, ScreenTriggerInputs(sessionCount = 1, daysSinceInstall = 0), traits)
        }

    @Test fun anUnknownOperatorDoesNotFire() = assertNull(shown("is_one_of", "pro", mapOf("plan" to "pro")))

    @Test fun notExistsDoesNotFireForAValue() = assertNull(shown("not_exists", null, mapOf("plan" to "pro")))

    @Test fun notExistsFiresForAnAbsentTrait() = assertEquals("s1", shown("not_exists", null, mapOf("other" to 1)))

    @Test fun aComparisonIsEvaluated() {
        assertEquals("s1", shown("gt", 2, mapOf("plan" to 3)))
        assertNull(shown("gt", 5, mapOf("plan" to 3)))
    }
}
