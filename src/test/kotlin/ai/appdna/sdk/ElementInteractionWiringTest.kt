package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.ContentBlock
import ai.appdna.sdk.onboarding.ElementInteractionResult
import ai.appdna.sdk.onboarding.RequiredFieldGate
import ai.appdna.sdk.onboarding.fireElementInteraction
import ai.appdna.sdk.onboarding.resolvedFieldConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure seams of the element-interaction wiring (Android mirror of iOS
 * ElementInteractionWiringTests): the delegate fire-fold, the required-field advance gate, and the
 * per-block field_config override read-layer. No Compose host needed.
 */
class ElementInteractionWiringTest {

    /** A fake delegate that returns a fixed [ElementInteractionResult], proving the fold applies
     * patches/overrides and reports advance. Only onElementInteraction is implemented; the rest come
     * from the interface's default methods. */
    private class FakeDelegate(private val result: ElementInteractionResult?) : AppDNAOnboardingDelegate {
        var received: Triple<String, String, String?>? = null
        override suspend fun onElementInteraction(
            flowId: String,
            stepId: String,
            blockId: String,
            action: String,
            value: String?,
            inputValues: Map<String, Any>,
        ): ElementInteractionResult? {
            received = Triple(blockId, action, value)
            return result
        }
    }

    // MARK: - 1. Fire-seam

    @Test
    fun fireSeamAppliesInputPatchesAndOverridesWithoutAdvance() = runBlocking {
        // Case A — inputValue patch + field_config override, advance = false.
        val delegate = FakeDelegate(
            ElementInteractionResult(
                fieldConfigPatches = mapOf("cal" to mapOf("highlight_color" to "#00FF00")),
                inputValuePatches = mapOf("otp" to "1234"),
                advance = false,
            ),
        )
        val (inputValues, overrides, advance) = fireElementInteraction(
            delegate = delegate,
            flowId = "f", stepId = "s", blockId = "otp",
            action = "otp_entered", value = "1234",
            inputValues = mapOf("existing" to "keep"),
            overrides = mapOf("cal" to mapOf("days_in_month" to 30)),
        )
        // Delegate saw the interaction.
        assertEquals("otp_entered", delegate.received?.second)
        // inputValues patched + untouched key preserved.
        assertEquals("1234", inputValues["otp"])
        assertEquals("keep", inputValues["existing"])
        // Overrides KEY-LEVEL merged — new key added, prior key retained (not blind-replaced).
        assertEquals("#00FF00", overrides["cal"]?.get("highlight_color"))
        assertEquals(30, overrides["cal"]?.get("days_in_month"))
        assertFalse(advance)
    }

    @Test
    fun fireSeamReportsAdvance() = runBlocking {
        // Case B — advance = true.
        val delegate = FakeDelegate(ElementInteractionResult(advance = true))
        val (_, _, advance) = fireElementInteraction(
            delegate = delegate,
            flowId = "f", stepId = "s", blockId = "confirm",
            action = "confirmed", value = null,
            inputValues = emptyMap(), overrides = emptyMap(),
        )
        assertTrue(advance)
    }

    @Test
    fun fireSeamWithNoDelegateIsNoOp() = runBlocking {
        val (inputValues, overrides, advance) = fireElementInteraction(
            delegate = null,
            flowId = "f", stepId = "s", blockId = "b",
            action = "day_selected", value = "5",
            inputValues = mapOf("k" to "v"), overrides = mapOf("x" to mapOf("a" to 1)),
        )
        assertEquals("v", inputValues["k"])
        assertEquals(1, overrides["x"]?.get("a"))
        assertFalse(advance)
    }

    // MARK: - 2. Advance gate

    @Test
    fun requiredFieldGateBlocksWhenEmptyAndPassesWhenFilled() {
        val required = ContentBlock(id = "q1", type = "input_text", field_required = true)

        // Empty → advance BLOCKED (an interaction-driven advance can't bypass validation).
        val empty = RequiredFieldGate.evaluate(listOf(required), emptyMap())
        assertFalse(empty.first)
        assertEquals("q1", empty.second)

        // Filled → advance allowed.
        val filled = RequiredFieldGate.evaluate(listOf(required), mapOf("q1" to "Alex"))
        assertTrue(filled.first)
        assertNull(filled.second)
    }

    @Test
    fun requiredFieldGateNoRequiredBlocksPasses() {
        val optional = ContentBlock(id = "q1", type = "input_text")
        assertTrue(RequiredFieldGate.evaluate(listOf(optional), emptyMap()).first)
        assertTrue(RequiredFieldGate.evaluate(emptyList(), emptyMap()).first)
    }

    // MARK: - 3. Override merge (read-layer)

    @Test
    fun resolvedFieldConfigCarriesOverride() {
        val cal = ContentBlock(id = "cal", type = "calendar_month", field_config = mapOf("today" to 5))
        val resolved = resolvedFieldConfig(cal, mapOf("cal" to mapOf("highlight_color" to "#00FF00")))
        // Override present…
        assertEquals("#00FF00", resolved.field_config?.get("highlight_color"))
        // …and the pre-existing key survives the merge.
        assertEquals(5, resolved.field_config?.get("today"))
    }

    @Test
    fun resolvedFieldConfigEmptyOverridesIsNoOp() {
        val cal = ContentBlock(id = "cal", type = "calendar_month", field_config = mapOf("today" to 5))
        val resolved = resolvedFieldConfig(cal, emptyMap())
        assertEquals(5, resolved.field_config?.get("today"))
        assertNull(resolved.field_config?.get("highlight_color"))
    }
}
