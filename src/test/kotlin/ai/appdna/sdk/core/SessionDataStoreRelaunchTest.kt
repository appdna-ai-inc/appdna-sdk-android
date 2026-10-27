package ai.appdna.sdk.core

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1150 — what the store persists reads back with the shape it was written in, after a relaunch.
 *
 * Android's `org.json` stores a `Map` or `List` given to `put` as-is and writes it as its `toString()`,
 * so a nested answer used to come back from disk as the string `"{city=Porto, …}"` / `"[a, b]"`.
 * `reloadFromDisk()` runs the loaders the initialiser runs, which is what a cold start does. The location
 * case is pinned for both platforms by the shared fixture `location_data_after_relaunch`; this covers
 * the other shapes the store holds.
 *
 * NEGATIVE CONTROL: against the store that `put` the raw value, the first three tests fail — the list
 * and the nested maps come back as Strings, and the NaN entry is kept as the text `"{x=NaN}"`.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDataStoreRelaunchTest {

    private lateinit var store: SessionDataStore

    @Before
    fun setUp() {
        SessionDataStore.initialize(ApplicationProvider.getApplicationContext())
        store = SessionDataStore.instance!!
        store.clearAll()
    }

    @After
    fun tearDown() = store.clearAll()

    @Test
    fun aListAnswerAndANestedMapAnswerSurviveARelaunch() {
        store.setOnboardingResponses(
            mapOf(
                "step_goals" to mapOf(
                    "goals" to listOf("sleep", "focus"),
                    "place" to mapOf("city" to "Q", "latitude" to 1.5, "tags" to listOf("a", "b")),
                    "count" to 3,
                ),
            ),
        )
        store.reloadFromDisk()
        val step = store.onboardingResponses["step_goals"]!!
        assertEquals(listOf("sleep", "focus"), step["goals"])
        assertEquals(mapOf("city" to "Q", "latitude" to 1.5, "tags" to listOf("a", "b")), step["place"])
        assertEquals(3, step["count"])
    }

    @Test
    fun aNestedSessionValueSurvivesARelaunch() {
        store.setSessionData("profile", mapOf("tier" to "gold", "flags" to listOf(true, false)))
        store.reloadFromDisk()
        assertEquals(mapOf("tier" to "gold", "flags" to listOf(true, false)), store.getSessionData("profile"))
    }

    @Test
    fun aNonFiniteNumberDeepInsideAnAnswerDropsOnlyThatEntry() {
        store.setOnboardingResponses(
            mapOf("s1" to mapOf("bad" to mapOf("x" to Double.NaN), "good" to mapOf("y" to 2))),
        )
        store.reloadFromDisk()
        val step = store.onboardingResponses["s1"]!!
        assertFalse("the entry holding a NaN is left out of the saved copy", step.containsKey("bad"))
        assertEquals(mapOf("y" to 2), step["good"])
    }

    @Test
    fun aJsonObjectValueIsStoredAsItsContents() {
        store.setSessionData("raw", JSONObject("""{"k":"v","n":[1,2]}"""))
        store.reloadFromDisk()
        val raw = store.getSessionData("raw")
        assertTrue("came back as a Map, got ${raw?.javaClass}", raw is Map<*, *>)
        assertEquals(mapOf("k" to "v", "n" to listOf(1, 2)), raw)
    }
}
