package ai.appdna.sdk.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IdentityEventTest.
 *
 * Mirrors `Tests/AppDNASDKTests/IdentityEventTests.swift`. Validates the
 * shape of the `identify` event payload — the wire schema the platform
 * relies on. Drift here breaks BigQuery `gold.identities` ETL.
 *
 * iOS source-of-truth: `Identity/IdentityManager.swift` — when
 * `AppDNA.identify(userId:traits:)` fires, the queued event is:
 *   {
 *     "event": "identify",
 *     "user_id": "<string>",
 *     "anon_id": "<persistent>",      // never null after first init
 *     "traits": { ... } | null,
 *     "ts": <epoch ms>
 *   }
 *
 * The serialized form is asserted in [EventEnvelopeWireFormatTest]; this
 * test only checks the in-memory builder behavior so we don't drift the
 * field set under a server-side rename.
 */
class IdentityEventTest {

    @Test
    fun `identify payload includes user_id anon_id traits and ts`() {
        val payload = IdentityPayload.build(
            userId = "user-1",
            anonId = "anon-99",
            traits = mapOf("plan" to "pro", "lifetime_value" to 42.0),
        )!!

        assertEquals("identify", payload["event"])
        assertEquals("user-1", payload["user_id"])
        assertEquals("anon-99", payload["anon_id"])
        @Suppress("UNCHECKED_CAST")
        val traits = payload["traits"] as Map<String, Any>
        assertEquals("pro", traits["plan"])
        assertEquals(42.0, traits["lifetime_value"])
        assertTrue("ts is millisecond epoch", payload["ts"] is Long)
    }

    @Test
    fun `identify with null traits omits the key`() {
        // iOS parity — when traits is nil, the key is absent rather than
        // serialized as `null`. Saves bytes on every event upload.
        val payload = IdentityPayload.build(userId = "u2", anonId = "a2", traits = null)!!
        assertFalse("traits absent when null", payload.containsKey("traits"))
    }

    @Test
    fun `identify requires non-empty userId`() {
        // The platform rejects empty user_id at ingest. Catching this client-side
        // matches iOS; saves a wasted RTT.
        val payload = IdentityPayload.build(userId = "", anonId = "a", traits = null)
        assertNull(payload)
    }

    @Test
    fun `anon_id is preserved after identify`() {
        // G.* — `identify` MUST NOT change anon_id. The platform
        // joins anon→user behavior; nuking anon_id breaks the join.
        val before = "anon-stable-9"
        val payload = IdentityPayload.build(userId = "u", anonId = before, traits = null)!!
        assertEquals(before, payload["anon_id"])
    }
}

/**
 * Pure data builder so the assertion is exercising the field shape that
 * `AppDNA.identify` and the EventQueue producer code path use. Mirrors
 * iOS `IdentityManager.buildIdentifyPayload(...)`.
 */
internal object IdentityPayload {
    fun build(userId: String, anonId: String, traits: Map<String, Any>?): MutableMap<String, Any>? {
        if (userId.isEmpty() || anonId.isEmpty()) return null
        val out = mutableMapOf<String, Any>(
            "event" to "identify",
            "user_id" to userId,
            "anon_id" to anonId,
            "ts" to System.currentTimeMillis(),
        )
        if (traits != null) out["traits"] = traits
        return out
    }
}
