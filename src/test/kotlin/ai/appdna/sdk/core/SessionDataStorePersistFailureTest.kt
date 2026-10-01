package ai.appdna.sdk.core

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * A value that cannot be written as JSON (NaN, infinity) is not persisted — visibly. `JSONObject.put`
 * throws on a non-finite number, and that exception used to escape into the host's
 * `setSessionData` call. Now the store logs a warning naming the entry (never the values) and leaves
 * only THAT entry out of the saved copy, as iOS `SessionDataStore.persistDict` does.
 *
 * NEGATIVE CONTROL: the store dropped the WHOLE saved copy for one bad value (the earlier "plan" was
 * gone from disk after a NaN "score"), so the first two tests fail on it.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDataStorePersistFailureTest {

    private lateinit var store: SessionDataStore
    /** The store's OWN preferences: the singleton may have been built with an earlier test's context. */
    private val prefs: android.content.SharedPreferences
        get() = SessionDataStore::class.java.getDeclaredField("prefs")
            .apply { isAccessible = true }
            .get(store) as android.content.SharedPreferences
    private val out = ByteArrayOutputStream()
    private lateinit var originalOut: PrintStream
    private lateinit var savedLevel: ai.appdna.sdk.LogLevel

    @Before
    fun setUp() {
        SessionDataStore.initialize(ApplicationProvider.getApplicationContext())
        store = SessionDataStore.instance!!
        store.clearAll()
        savedLevel = ai.appdna.sdk.Log.level
        ai.appdna.sdk.Log.level = ai.appdna.sdk.LogLevel.WARNING
        originalOut = System.out
        System.setOut(PrintStream(out, true))
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        ai.appdna.sdk.Log.level = savedLevel
        store.clearAll()
    }

    private fun logged() = out.toString(Charsets.UTF_8.name())

    private fun saved(key: String) = prefs.getString(key, null)?.let { org.json.JSONObject(it) }

    @Test
    fun `a NaN session value does not throw, is logged, and only that key is left out`() {
        store.setSessionData("plan", "pro")
        assertNotNull(prefs.getString("session_data", null))

        store.setSessionData("score", Double.NaN)

        assertEquals("the other values are still saved", "pro", saved("session_data")?.optString("plan"))
        assertFalse("the NaN entry is left out", saved("session_data")!!.has("score"))
        val lines = logged().lines().filter { it.contains("SessionDataStore") && it.contains("session_data") && it.contains("score") }
        assertEquals(logged(), 1, lines.size)
        assertFalse("values are never logged", logged().contains("pro"))
        assertEquals("pro", store.getSessionData("plan"))
    }

    @Test
    fun `an infinite onboarding answer does not throw and only that answer is left out`() {
        store.setOnboardingResponses(mapOf("s" to mapOf("name" to "Ada")))
        assertNotNull(prefs.getString("onboarding_responses", null))

        store.setOnboardingResponses(mapOf(
            "s" to mapOf("lat" to Double.POSITIVE_INFINITY, "name" to "Ada"),
            "t" to mapOf("plan" to "pro"),
        ))

        val savedResponses = saved("onboarding_responses")!!
        assertEquals("Ada", savedResponses.getJSONObject("s").optString("name"))
        assertFalse(savedResponses.getJSONObject("s").has("lat"))
        assertEquals("pro", savedResponses.getJSONObject("t").optString("plan"))
        assertTrue(logged(), logged().contains("onboarding_responses") && logged().contains("s.lat"))
    }

    @Test
    fun `a valid value logs nothing`() {
        store.setSessionData("plan", "pro")
        assertFalse(logged(), logged().contains("SessionDataStore"))
    }
}
