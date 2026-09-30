package ai.appdna.sdk.core

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * `setSessionData` call. Now the store logs a warning naming the key (never the values) and clears the
 * saved copy, as iOS `SessionDataStore.persistDict` does.
 *
 * NEGATIVE CONTROL: against the old `persistMap(key, flatMapToJson(updated))` the first test fails with
 * `org.json.JSONException: Forbidden numeric value: NaN` thrown out of `setSessionData`.
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

    @Test
    fun `a NaN session value does not throw, is logged, and clears the stale copy`() {
        store.setSessionData("plan", "pro")
        assertNotNull(prefs.getString("session_data", null))

        store.setSessionData("score", Double.NaN)

        assertNull("the earlier copy must not survive", prefs.getString("session_data", null))
        val lines = logged().lines().filter { it.contains("SessionDataStore") && it.contains("session_data") }
        assertEquals(logged(), 1, lines.size)
        assertFalse("values are never logged", logged().contains("pro"))
        assertEquals("pro", store.getSessionData("plan"))
    }

    @Test
    fun `an infinite onboarding answer does not throw and clears the stale copy`() {
        store.setOnboardingResponses(mapOf("s" to mapOf("name" to "Ada")))
        assertNotNull(prefs.getString("onboarding_responses", null))

        store.setOnboardingResponses(mapOf("s" to mapOf("lat" to Double.POSITIVE_INFINITY)))

        assertNull(prefs.getString("onboarding_responses", null))
        assertTrue(logged(), logged().contains("onboarding_responses"))
    }

    @Test
    fun `a valid value logs nothing`() {
        store.setSessionData("plan", "pro")
        assertFalse(logged(), logged().contains("SessionDataStore"))
    }
}
