package ai.appdna.sdk

import ai.appdna.sdk.events.EventSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event envelope schema tests — verify format matches iOS.
 */
class EventSchemaTest {

    @Test
    fun testSchemaVersion() {
        assertEquals(1, EventSchema.SCHEMA_VERSION)
    }

    @Test
    fun testSdkVersion() {
        // SDK_VERSION is now a live reference to AppDNA.sdkVersion,
        // not a stale literal. Verify they stay in sync — drift is the regression we
        // are guarding against.
        assertEquals(AppDNA.sdkVersion, EventSchema.SDK_VERSION)
    }

    @Test
    fun testEnvelopeStructure() {
        val identity = DeviceIdentity(anonId = "anon_123", userId = "user_456")
        val envelope = EventSchema.buildEnvelope(
            eventName = "test_event",
            properties = mapOf("key" to "value"),
            identity = identity,
            sessionId = "session_789",
            appVersion = "1.0.0",
            analyticsConsent = true
        )

        // Top-level fields
        assertEquals(1, envelope.getInt("schema_version"))
        assertNotNull(envelope.getString("event_id"))
        assertEquals("test_event", envelope.getString("event_name"))
        assertTrue(envelope.getLong("ts_ms") > 0)

        // User
        val user = envelope.getJSONObject("user")
        assertEquals("anon_123", user.getString("anon_id"))
        assertEquals("user_456", user.getString("user_id"))

        // Device
        val device = envelope.getJSONObject("device")
        assertEquals("android", device.getString("platform"))
        // sdk_version reads through to AppDNA.sdkVersion at call time.
        assertEquals(AppDNA.sdkVersion, device.getString("sdk_version"))
        assertEquals("1.0.0", device.getString("app_version"))

        // Context
        val context = envelope.getJSONObject("context")
        assertEquals("session_789", context.getString("session_id"))

        // Properties
        val props = envelope.getJSONObject("properties")
        assertEquals("value", props.getString("key"))

        // Privacy
        val privacy = envelope.getJSONObject("privacy")
        val consent = privacy.getJSONObject("consent")
        assertTrue(consent.getBoolean("analytics"))
    }

    @Test
    fun testEnvelopeWithoutUserId() {
        val identity = DeviceIdentity(anonId = "anon_only")
        val envelope = EventSchema.buildEnvelope(
            eventName = "anonymous_event",
            properties = null,
            identity = identity,
            sessionId = "sess",
            appVersion = "1.0.0",
            analyticsConsent = true
        )

        val user = envelope.getJSONObject("user")
        assertEquals("anon_only", user.getString("anon_id"))
        assertTrue(!user.has("user_id"))
    }

    @Test
    fun testEnvelopeWithoutProperties() {
        val identity = DeviceIdentity(anonId = "anon")
        val envelope = EventSchema.buildEnvelope(
            eventName = "simple_event",
            properties = null,
            identity = identity,
            sessionId = "sess",
            appVersion = "1.0.0",
            analyticsConsent = true
        )

        assertTrue(!envelope.has("properties"))
    }

    @Test
    fun testEventIdIsUuid() {
        val identity = DeviceIdentity(anonId = "anon")
        val envelope = EventSchema.buildEnvelope(
            eventName = "test",
            properties = null,
            identity = identity,
            sessionId = "sess",
            appVersion = "1.0.0",
            analyticsConsent = true
        )

        val eventId = envelope.getString("event_id")
        // UUID format: 8-4-4-4-12 hex chars
        assertTrue(eventId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
}
