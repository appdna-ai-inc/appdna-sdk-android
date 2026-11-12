package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An "Add widget" button's how-to sheets are block lists the SDK walkers must see: the decoder
 * decodes them as blocks at any depth, the raw host-data pass stamps and resolves them as it does
 * children, and the step's Google credit walk skips them (a modal above the step) while the how-to
 * sheet's own walk sees them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetHowtoWalkersTest {

    private val button: Map<String, Any> = mapOf(
        "id" to "cta", "type" to "button", "action" to "add_widget", "text" to "Add",
        "howto_blocks_ios" to listOf(mapOf("type" to "heading", "text" to "iOS steps")),
        "howto_blocks_android" to listOf(
            mapOf("type" to "heading", "text" to "Near {{responses.place.name}}"),
            mapOf("id" to "ha_done", "type" to "button", "text" to "Done", "action" to "next"),
        ),
    )

    private fun decode(vararg blocks: Map<String, Any>): List<ContentBlock> =
        OnboardingConfigParser.parseStepForTest(
            mapOf("id" to "s", "type" to "content", "config" to mapOf("content_blocks" to blocks.toList())),
        )!!.config.content_blocks!!.toList()

    @Test fun `the decoder decodes both how-to lists as blocks, top level and nested`() {
        val top = decode(button).single()
        assertEquals(listOf("heading", "button"), top.howto_blocks_android!!.map { it.type })
        assertEquals("iOS steps", top.howto_blocks_ios!!.single().text)
        val nested = decode(mapOf("id" to "row", "type" to "row", "stack_children" to listOf(button))).single()
        val child = (nested.children ?: nested.stack_children)!!.single()
        assertEquals("ha_done", child.howto_blocks_android!![1].id)
        // A block without how-to keys carries none, and its field_config is untouched.
        val plain = decode(mapOf("id" to "p", "type" to "button", "text" to "Next")).single()
        assertEquals(null, plain.howto_blocks_android)
        assertEquals(null, plain.field_config)
    }

    @Test fun `the raw host-data pass stamps ids inside both how-to lists`() {
        val stamped = HostDataResolver.stampBlockIds(button, "step8", 2)
        val android = stamped["howto_blocks_android"] as List<*>
        val ios = stamped["howto_blocks_ios"] as List<*>
        assertEquals("step8/2/howto_blocks_android/0", (android[0] as Map<*, *>)["id"])
        assertEquals("ha_done", (android[1] as Map<*, *>)["id"])
        assertEquals("step8/2/howto_blocks_ios/0", (ios[0] as Map<*, *>)["id"])
    }

    @Test fun `the step's Google credit skips the how-to and the how-to's own walk sees it`() {
        val answers = GoogleCredit.Answers(
            accumulated = mapOf("place" to mapOf("place_id" to "synthetic_place", "name" to "A cafe")),
            stepInputs = emptyMap(), sessionStore = emptyMap(),
        )
        val block = decode(button).single()
        assertFalse("the step credits a how-to it does not draw", GoogleCredit.walk(GoogleCredit.blockSource(block), answers))
        assertTrue(GoogleCredit.howtoShows(block.howto_blocks_android!!, answers))
        // Control: the same token on the step itself does credit, so the walk can see it.
        assertTrue(GoogleCredit.walk(GoogleCredit.blockSource(block.copy(text = "Near {{responses.place.name}}")), answers))
    }
}
