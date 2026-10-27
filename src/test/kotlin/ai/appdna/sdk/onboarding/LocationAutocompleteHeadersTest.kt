package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The server decides from `x-sdk-platform` / `x-sdk-version` which answer shape a build reads, and which builds are
 * shown Google suggestions at all; a request without them is treated as the oldest build. iOS asserts its headers in
 * `LocationAutocompleteTests`; this is Android's, for both the form field and the block, which share the request.
 */
class LocationAutocompleteHeadersTest {
    @Test
    fun sendsTheSdkPlatformAndVersion() {
        val headers = locationAutocompleteHeaders("pk_test")
        assertEquals("android", headers["x-sdk-platform"])
        assertEquals(ai.appdna.sdk.AppDNA.sdkVersion, headers["x-sdk-version"])
        assertEquals("pk_test", headers["x-api-key"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun omitsTheApiKeyWhenThereIsNone() {
        assertFalse(locationAutocompleteHeaders(null).containsKey("x-api-key"))
    }
}
