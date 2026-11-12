package ai.appdna.sdk.messages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * #1731 — a journey's in-app step to an anonymous user was marked sent and never drawn: the listener started only at
 * identify. These tests pin the rule ([PendingMessageListener.listenIds]) and run the shared fixture's script through
 * the real listener twice — once as the SDK now follows identity, once with the pre-#1731 rule (nothing before
 * identify, the user id only after it) as the RED CONTROL: the same harness must see that rule lose the anonymous
 * deliveries, or a green run of the fixture proves nothing.
 */
class PendingMessageFollowTest {

    private fun fixtureAction(): JSONObject {
        val rel = "sdk-shared-fixtures/action_dispatch/pending_messages_follow_identity.fixture.json"
        var dir: File? = File(".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            listOf(File(dir, rel), File(dir, "packages/$rel")).firstOrNull { it.exists() }?.let { found = it }
            dir = dir.parentFile
        }
        if (found == null) System.getenv("APPDNA_SDK_FIXTURES_DIR")?.let { env ->
            File(env, "action_dispatch/pending_messages_follow_identity.fixture.json").takeIf { it.exists() }?.let { found = it }
        }
        assertNotNull("pending_messages_follow_identity.fixture.json not found", found)
        return JSONObject(found!!.readText()).getJSONObject("action")
    }

    @Test
    fun listenIdsFollowIdentity() {
        assertEquals(listOf("a"), PendingMessageListener.listenIds("a", null))
        assertEquals(listOf("a", "u"), PendingMessageListener.listenIds("a", "u"))
        assertEquals(listOf("a"), PendingMessageListener.listenIds("a", "a"))
        assertEquals(listOf("a"), PendingMessageListener.listenIds("a", ""))
        assertEquals(listOf("u"), PendingMessageListener.listenIds(null, "u"))
    }

    @Test
    fun fixtureScriptShowsAnonymousAndIdentifiedDeliveriesOnce() {
        val state = PendingFollowRig.run(fixtureAction())!!
        assertEquals(listOf("anon-1/d1", "user-1/d2", "anon-1/d3", "anon-1/d5"), state["presented"])
        assertEquals(listOf("user-1/d4"), state["unconsumed"])
    }

    /**
     * RED CONTROL — the pre-#1731 rule through the same listener and rig: only the delivery addressed to the user id
     * after identify is drawn; the one written before launch, the anonymous enrolment's later step and the one after
     * sign-out are all lost, and stay unconsumed.
     */
    @Test
    fun redControlUserOnlyRuleLosesAnonymousDeliveries() {
        val state = PendingFollowRig.run(fixtureAction(), legacyUserOnly = true)!!
        assertEquals(listOf("user-1/d2"), state["presented"])
        assertEquals(listOf("anon-1/d1", "anon-1/d3", "anon-1/d5", "user-1/d4"), state["unconsumed"])
    }
}
