package ai.appdna.sdk.events

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.AppDNAOptions
import ai.appdna.sdk.DeviceIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format integration test that locks the JSON shape
 * Android emits against iOS Codable behavior.
 *
 * iOS rule (Sources/AppDNASDK/Events/EventSchema.swift `SDKEvent.encode(to:)`):
 *   - `properties: [String: AnyCodable]?` — when null OR empty, the field is
 *     omitted entirely from the envelope (Codable optional encoding).
 *   - `experiment_exposures` — likewise omitted when nil/empty.
 *
 * Android must mirror this so cross-platform analytics ingest sees a stable
 * shape regardless of which SDK emitted the event.
 *
 * Are also covered here so the field-level
 * serialization is locked alongside the omit-when-empty rule.
 */
class EventEnvelopeWireFormatTest {

    private val identity = DeviceIdentity(anonId = "anon-1", userId = "user-2")

    @Test
    fun propertiesField_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertFalse(
            "properties must be omitted when null (iOS Codable parity)",
            envelope.has("properties")
        )
    }

    @Test
    fun propertiesField_isOmitted_whenEmptyMap() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = emptyMap(),
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertFalse(
            "properties must be omitted when empty (iOS Codable parity)",
            envelope.has("properties")
        )
    }

    @Test
    fun propertiesField_isPresent_whenNonEmpty() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = mapOf("k" to "v"),
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        assertTrue(envelope.has("properties"))
        assertEquals("v", envelope.getJSONObject("properties").getString("k"))
    }

    @Test
    fun experimentExposures_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = null,
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("experiment_exposures"))
    }

    @Test
    fun experimentExposures_isOmitted_whenEmpty() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = emptyList(),
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("experiment_exposures"))
    }

    @Test
    fun experimentExposures_emitsTuples_whenPresent() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            experimentExposures = listOf(
                ExperimentExposure("exp-A", "var-1"),
                ExperimentExposure("exp-B", "var-2"),
            ),
        )
        val arr = envelope.getJSONObject("context").getJSONArray("experiment_exposures")
        assertEquals(2, arr.length())
        assertEquals("exp-A", arr.getJSONObject(0).getString("exp"))
        assertEquals("var-1", arr.getJSONObject(0).getString("variant"))
        assertEquals("exp-B", arr.getJSONObject(1).getString("exp"))
    }

    // Environment is omitted when null, present when supplied.
    @Test
    fun environment_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            environment = null,
        )
        assertFalse(envelope.has("environment"))
    }

    @Test
    fun environment_isPresent_whenSupplied() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            environment = "sandbox",
        )
        assertEquals("sandbox", envelope.getString("environment"))
    }

    // context.screen is omitted when null, present when supplied.
    @Test
    fun contextScreen_isOmitted_whenNull() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            screen = null,
        )
        val context = envelope.getJSONObject("context")
        assertFalse(context.has("screen"))
    }

    @Test
    fun contextScreen_isPresent_whenSupplied() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
            screen = "HomeFeed",
        )
        val context = envelope.getJSONObject("context")
        assertEquals("HomeFeed", context.getString("screen"))
    }

    // device.os is normalized to a 3-part dotted string.
    @Test
    fun deviceOs_isNormalizedToThreeParts() {
        // We can't easily mock Build.VERSION.RELEASE in unit tests so we test
        // the helper directly. The envelope path simply forwards to it.
        assertEquals("14.0.0", EventSchema.normalizeOsVersion("14"))
        assertEquals("14.0.0", EventSchema.normalizeOsVersion("14.0"))
        assertEquals("14.0.1", EventSchema.normalizeOsVersion("14.0.1"))
        assertEquals("0.0.0", EventSchema.normalizeOsVersion(""))
        assertEquals("0.0.0", EventSchema.normalizeOsVersion(null))
        // 4+ parts are passed through (iOS does the same).
        assertEquals("14.0.1.2", EventSchema.normalizeOsVersion("14.0.1.2"))
    }

    // device.locale is BCP-47 not legacy `Locale.toString()`.
    @Test
    fun deviceLocale_isBcp47() {
        val envelope = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        )
        val locale = envelope.getJSONObject("device").getString("locale")
        // BCP-47 uses '-', legacy uses '_'. Whatever the host's default is,
        // the field must NEVER contain an underscore.
        assertFalse(
            "device.locale must be BCP-47 (no underscores)",
            locale.contains("_")
        )
        // Must not be empty or null
        assertNotNull(locale)
        assertTrue(locale.isNotBlank())
    }

    /**
     * `device.framework_version` is the WRAPPER's version.
     *
     * `device.sdk_version` is always this native core's version, so in BigQuery a React Native app was
     * indistinguishable from a native one on every version column: all 79 `react_native` rows in
     * `raw.sdk_events` carried `sdk_version = 1.0.70`, the core they wrap. The wrapper's own version
     * reached `options.frameworkVersion` and was then read by exactly ONE thing — `diagnose()` —
     * which prints to the developer's console and never leaves the device. So "is the fix in the
     * version they are running?" had no answer for any wrapper user.
     *
     * Omitted (not null, not empty-string) for a native host, so a native envelope keeps the exact
     * shape iOS emits — the server schema is strip-mode and the BQ column is nullable, and a
     * fabricated version would be worse than a missing one.
     */
    @After
    fun resetOptions() {
        AppDNA.options = AppDNAOptions()
    }

    @Test
    fun deviceFrameworkVersion_isOmitted_forNativeHost() {
        AppDNA.options = AppDNAOptions()
        val device = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        ).getJSONObject("device")

        assertEquals("native", device.getString("framework"))
        assertFalse(
            "a native host has no wrapper version — the key must be absent, not null",
            device.has("framework_version")
        )
    }

    @Test
    fun deviceFrameworkVersion_isEmitted_forWrapperHost_besideTheNativeSdkVersion() {
        AppDNA.options = AppDNAOptions(framework = "react_native", frameworkVersion = "1.0.7")
        val device = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        ).getJSONObject("device")

        assertEquals("react_native", device.getString("framework"))
        assertEquals("1.0.7", device.getString("framework_version"))
        // BESIDE, not instead of: sdk_version stays the native core's, which is what it has always
        // meant. Overloading it would silently rewrite the meaning of an existing BQ column.
        assertEquals(
            "sdk_version must remain the NATIVE core version",
            AppDNA.sdkVersion,
            device.getString("sdk_version")
        )
    }

    @Test
    fun deviceFrameworkVersion_isOmitted_whenAWrapperReportsBlank() {
        // A wrapper that forgets to inject its version must not stamp an empty string into the column:
        // "" reads like an answer in a GROUP BY, and it is not one.
        AppDNA.options = AppDNAOptions(framework = "react_native", frameworkVersion = "   ")
        val device = EventSchema.buildEnvelope(
            eventName = "evt",
            properties = null,
            identity = identity,
            sessionId = "s1",
            appVersion = "1.2.3",
            analyticsConsent = true,
        ).getJSONObject("device")

        assertFalse(device.has("framework_version"))
    }

    @Test
    fun deviceTimezone_isTheDefaultZonesIanaId_readPerEvent() {
        // The server schedules time-zone-aware pushes, quiet hours and journey waits from this field, so
        // it must be the platform's CURRENT zone at build time (a traveller moves), as an IANA id.
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Chatham"))
            val first = EventSchema.buildEnvelope(
                eventName = "evt", properties = null, identity = identity, sessionId = "s1",
                appVersion = "1.2.3", analyticsConsent = true,
            ).getJSONObject("device")
            assertEquals("Pacific/Chatham", first.getString("timezone"))

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
            val second = EventSchema.buildEnvelope(
                eventName = "evt", properties = null, identity = identity, sessionId = "s1",
                appVersion = "1.2.3", analyticsConsent = true,
            ).getJSONObject("device")
            assertEquals("America/Sao_Paulo", second.getString("timezone"))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
