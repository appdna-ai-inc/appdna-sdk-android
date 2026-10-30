package ai.appdna.sdk.config

import ai.appdna.sdk.messages.MessageConfigParser
import ai.appdna.sdk.storage.LocalStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Per-item flag + message serving (Android read side). Covers the exact logic where the
 * earlier bugs lived: flag `.value` unwrap, null-flag → unset, prune-to-index-keyset (removal),
 * empty-set clear, and the `parseMessages` cold-start wrapper-unwrap. Mirrors the iOS suite.
 */
@RunWith(RobolectricTestRunner::class)
class PerItemFlagMessageServingTest {

    private fun makeRcm(): RemoteConfigManager {
        val ctx = RuntimeEnvironment.getApplication() as android.content.Context
        return RemoteConfigManager("orgs/o/apps/a", LocalStorage(ctx), 3600L)
    }

    @Test fun flagValueUnwrap_boolTrue() {
        val rcm = makeRcm(); val ff = FeatureFlagManager(rcm)
        rcm.parseFlagDocForTesting("f", mapOf("key" to "f", "value" to true, "type" to "boolean"))
        assertEquals(true, rcm.getConfig("f"))   // raw value, not the wrapper map
        assertTrue(ff.isEnabled("f"))
    }

    @Test fun flagValueUnwrap_boolFalseNotDropped() {
        val rcm = makeRcm(); val ff = FeatureFlagManager(rcm)
        rcm.parseFlagDocForTesting("f", mapOf("key" to "f", "value" to false, "type" to "boolean"))
        assertEquals(false, rcm.getConfig("f"))   // false preserved (not unset)
        assertFalse(ff.isEnabled("f"))
    }

    @Test fun flagValueUnwrap_numberAndString() {
        val rcm = makeRcm()
        rcm.parseFlagDocForTesting("n", mapOf("value" to 7, "type" to "number"))
        rcm.parseFlagDocForTesting("s", mapOf("value" to "blue", "type" to "string"))
        assertEquals(7, rcm.getConfig("n"))
        assertEquals("blue", rcm.getConfig("s"))
    }

    @Test fun nullValuedFlagIsUnset() {
        val rcm = makeRcm()
        // A flag served with an explicit null value (Firestore returns the "value" key present, = null) ⇒
        // the key is omitted (parity with iOS NSNull handling).
        val doc = HashMap<String, Any?>().apply { put("key", "x"); put("value", null); put("type", "string") }
        @Suppress("UNCHECKED_CAST")
        rcm.parseFlagDocForTesting("x", doc as Map<String, Any>)
        assertNull(rcm.getConfig("x"))
    }

    @Test fun pruneRemovesFlagNotInIndex() {
        val rcm = makeRcm()
        rcm.parseFlagDocForTesting("a", mapOf("value" to true))
        rcm.parseFlagDocForTesting("b", mapOf("value" to true))
        rcm.pruneFlagsForTesting(setOf("a"))     // index now lists only "a"
        assertEquals(true, rcm.getConfig("a"))
        assertNull(rcm.getConfig("b"))            // removed flag stops serving
    }

    @Test fun emptyIndexClearsAllFlags() {
        val rcm = makeRcm()
        rcm.parseFlagDocForTesting("a", mapOf("value" to true))
        rcm.pruneFlagsForTesting(emptySet())      // empty index ⇒ clear
        assertNull(rcm.getConfig("a"))
        assertTrue(rcm.getAllConfig().isEmpty())
    }

    @Test fun perItemMessageDecodesAndPrunes() {
        val rcm = makeRcm()
        rcm.parseMessageDocForTesting("m1", mapOf("name" to "Winback", "message_type" to "modal",
            "content" to mapOf("title" to "Come back"), "trigger_rules" to mapOf("event" to "app_open")))
        rcm.parseMessageDocForTesting("m2", mapOf("name" to "B", "message_type" to "modal"))
        assertEquals("Winback", rcm.messagesForTesting()["m1"]?.name)
        rcm.pruneMessagesForTesting(setOf("m1"))
        assertNotNull(rcm.messagesForTesting()["m1"])
        assertNull(rcm.messagesForTesting()["m2"])   // removed message stops serving
    }

    // ── R2 regression: parseMessages must unwrap {messages:{id:cfg}} (cold-start / mega-doc shape) ──
    @Test fun parseMessagesUnwrapsWrappedShape() {
        val wrapped = mapOf("version" to 1, "messages" to mapOf(
            "m1" to mapOf("name" to "Wrapped", "message_type" to "modal")))
        val parsed = MessageConfigParser.parseMessages(wrapped)
        assertEquals(1, parsed.size)
        assertEquals("Wrapped", parsed["m1"]?.name)   // NOT one bogus message keyed "messages"
        assertNull(parsed["messages"])
    }

    @Test fun parseMessagesAcceptsFlatShape() {
        val flat = mapOf("m1" to mapOf("name" to "Flat", "message_type" to "modal"))
        val parsed = MessageConfigParser.parseMessages(flat)
        assertEquals("Flat", parsed["m1"]?.name)
    }
}
