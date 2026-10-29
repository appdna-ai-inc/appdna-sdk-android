package ai.appdna.sdk

import ai.appdna.sdk.storage.LocalStorage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A JSON null in an identify trait survives a relaunch.
 *
 * The React Native bridge passes every JS `null` as `JSONObject.NULL`, and the object its
 * `getLocationData` resolves holds one for each unset key. The identity store keeps those traits in
 * memory (the shared fixture `identify_trait_null_values` covers the event and the in-memory state);
 * this covers the other half: a new [IdentityManager] over the same storage, which is what the next
 * launch sees. The Swift store keeps `NSNull` across launches the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentifyNullTraitsPersistedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aNullTraitAndATraitHoldingNullsAreStillThereAfterARelaunch() {
        val storage = LocalStorage(context)
        IdentityManager(storage).identify(
            "user_null_1",
            mapOf(
                "plan" to "pro",
                "referrer" to JSONObject.NULL,
                "home" to mapOf("formatted_address" to "1 Example Street", "city" to null, "latitude" to 1.5),
            ),
        )

        val relaunched = IdentityManager(storage).currentIdentity.traits.orEmpty()

        assertEquals(setOf("plan", "referrer", "home"), relaunched.keys)
        assertSame(JSONObject.NULL, relaunched["referrer"])
        val home = relaunched["home"] as JSONObject
        assertTrue("an unset location key is kept as null", home.has("city") && home.isNull("city"))
        assertEquals("1 Example Street", home.getString("formatted_address"))
    }
}
