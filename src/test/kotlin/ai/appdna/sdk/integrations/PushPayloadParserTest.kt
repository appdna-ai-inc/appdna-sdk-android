package ai.appdna.sdk.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FCM payload parse was private on a `FirebaseMessagingService` subclass (so it
 * had no coverage at all), and the `PushPayload` the host delegate received carried only the single
 * primary `action` — a push with three buttons looked identical to one with none.
 */
class PushPayloadParserTest {

    @Test
    fun `the delegate payload carries every action button`() {
        val payload = PushPayloadParser.buildPayload(
            mapOf(
                "push_id" to "p1",
                "title" to "Come back",
                "body" to "Your streak is at risk",
                "actions" to """
                    [
                      {"id":"open","label":"Open","type":"deep_link","value":"myapp://streak"},
                      {"id":"later","label":"Later","type":"dismiss"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals("p1", payload.pushId)
        assertEquals(2, payload.actions.size)
        assertEquals("open", payload.actions[0].id)
        assertEquals("Open", payload.actions[0].label)
        assertEquals("deep_link", payload.actions[0].type)
        assertEquals("myapp://streak", payload.actions[0].value)
        assertEquals("later", payload.actions[1].id)
        assertEquals("dismiss", payload.actions[1].type)
        assertNull(payload.actions[1].value)
    }

    @Test
    fun `the legacy flat schema still yields buttons, uncapped`() {
        val data = buildMap {
            put("push_id", "p2")
            repeat(4) { i ->
                put("action_${i}_id", "a$i")
                put("action_${i}_label", "Label $i")
                put("action_${i}_type", "show_screen")
                put("action_${i}_value", "screen$i")
            }
        }
        val buttons = PushPayloadParser.parseActionButtons(data)
        assertEquals(4, buttons.size)
        assertEquals("a3", buttons[3].id)
        assertEquals("screen3", buttons[3].value)
    }

    @Test
    fun `the codegen action_type - action_value spelling is accepted too`() {
        val buttons = PushPayloadParser.parseActionButtons(
            mapOf("actions" to """[{"id":"open","label":"Open","action_type":"deep_link","action_value":"myapp://x"}]"""),
        )
        assertEquals(1, buttons.size)
        assertEquals("deep_link", buttons[0].type)
        assertEquals("myapp://x", buttons[0].value)
    }

    @Test
    fun `the single canonical action keeps working alongside actions`() {
        val payload = PushPayloadParser.buildPayload(
            mapOf(
                "push_id" to "p3",
                "action" to """{"type":"show_screen","value":"settings"}""",
                "actions" to """[{"id":"open","label":"Open","type":"dismiss"}]""",
            ),
        )
        assertEquals("show_screen", payload.action?.type)
        assertEquals("settings", payload.action?.value)
        assertEquals(1, payload.actions.size)
    }

    @Test
    fun `legacy flat action_type falls back when no canonical action object is present`() {
        val action = PushPayloadParser.parseCanonicalAction(
            mapOf("action_type" to "deep_link", "action_value" to "myapp://y"),
        )
        assertEquals("deep_link", action?.type)
        assertEquals("myapp://y", action?.value)
    }

    @Test
    fun `malformed actions JSON degrades to no buttons instead of throwing`() {
        val payload = PushPayloadParser.buildPayload(mapOf("push_id" to "p4", "actions" to "{not json"))
        assertTrue(payload.actions.isEmpty())
    }
}
