package ai.appdna.sdk.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the presented flow supplies to label an answer no pick was recorded for, read off a parsed
 * flow the way `showFlow` hands it over. Synthetic shapes only.
 */
@RunWith(RobolectricTestRunner::class)
class FlowOptionRecordsTest {

    private fun flow(): OnboardingFlowConfig = OnboardingConfigParser.parseFlowForTest(
        "f",
        mapOf(
            "id" to "f", "name" to "f", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf(
                mapOf(
                    "id" to "s1", "type" to "form", "name" to "One",
                    "content_blocks" to listOf(
                        mapOf(
                            "id" to "sel", "type" to "input_select", "field_id" to "goal",
                            "field_options" to listOf(
                                mapOf("id" to "lose_weight", "label" to "Lose weight"),
                                mapOf("id" to "o2", "value" to "gain", "label" to "Gain muscle"),
                                mapOf("id" to "tmpl", "label" to "{{hook_data.x}}"),
                            ),
                        ),
                        mapOf(
                            "id" to "stack", "type" to "stack",
                            "children" to listOf(
                                mapOf(
                                    "id" to "nest", "type" to "input_chips", "field_id" to "tags",
                                    "field_options" to listOf(mapOf("id" to "t1", "label" to "Tag one")),
                                ),
                            ),
                        ),
                    ),
                    "config" to mapOf(
                        "fields" to listOf(
                            mapOf(
                                "id" to "plan", "type" to "select", "label" to "Plan",
                                "options" to listOf(mapOf("id" to "pro", "label" to "Pro")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `reads every input's options by field and stored value`() {
        val records = SelectedOptionStore.optionRecords(flow())
        assertEquals(
            listOf(mapOf("value" to "lose_weight", "label" to "Lose weight"), mapOf("value" to "gain", "label" to "Gain muscle")),
            records["goal"],
        )
        assertEquals(listOf(mapOf("value" to "t1", "label" to "Tag one")), records["tags"])
        assertEquals(listOf(mapOf("value" to "pro", "label" to "Pro")), records["plan"])
        assertFalse("a label that is still a template is left out", records.getValue("goal").any { it["value"] == "tmpl" })
    }

    @Test
    fun `a flow set on the store labels an answer with no recorded pick and a recorded pick still wins`() {
        SelectedOptionStore.resetForTesting()
        SelectedOptionStore.setFlowOptions(flow())
        assertEquals("Lose weight", SelectedOptionStore.drawnAnswer("goal", "lose_weight", SelectedOptionStore.drawingRecords()))
        SelectedOptionStore.seedForTesting("goal", mapOf("value" to "lose_weight", "label" to "Lose it"))
        assertEquals("Lose it", SelectedOptionStore.drawnAnswer("goal", "lose_weight", SelectedOptionStore.drawingRecords()))
        SelectedOptionStore.resetForTesting()
    }

    private fun quiz(): OnboardingFlowConfig = OnboardingConfigParser.parseFlowForTest(
        "q",
        mapOf(
            "id" to "q", "name" to "q", "version" to 1, "settings" to emptyMap<String, Any>(),
            "steps" to listOf("first" to "Alpha first", "second" to "Alpha second").mapIndexed { i, (step, label) ->
                mapOf(
                    "id" to "q${i + 1}", "type" to "form", "name" to step,
                    "content_blocks" to listOf(
                        mapOf("id" to "b$i", "type" to "input_select", "field_id" to "pick",
                            "field_options" to listOf(mapOf("id" to "a", "label" to label))),
                    ),
                )
            },
        ),
    )

    @Test
    fun `each step's options are kept under its own key and an answer reads its own step's label`() {
        val records = SelectedOptionStore.optionRecords(quiz())
        assertEquals(listOf(mapOf("value" to "a", "label" to "Alpha first")), records["q1/pick"])
        assertEquals(listOf(mapOf("value" to "a", "label" to "Alpha second")), records["q2/pick"])
        // The latest tap was on q2; q1's answer still draws q1's label.
        val selected = SelectedOptionStore.drawingRecords(mapOf("pick" to mapOf("value" to "a", "label" to "Alpha second")), records)
        assertEquals("Alpha first", SelectedOptionStore.drawnAnswerAt("responses.q1.pick", "a", selected))
        assertEquals("Alpha second", SelectedOptionStore.drawnAnswerAt("responses.q2.pick", "a", selected))
    }
}
