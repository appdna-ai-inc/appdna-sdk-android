package ai.appdna.sdk.onboarding

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Select drawn as a dropdown (the default `display_style`) opens the picked option's
 * `sheet_blocks`, as the list, stacked and grid styles do. Each case renders the REAL block renderer,
 * opens the dropdown by clicking it, and clicks a row of the menu that opens — the same gestures a
 * user makes. The Android half of `DropdownOptionSheetTests.swift`. Every option and sheet is
 * synthetic.
 *
 * Android's menu has no placeholder row, so "back to the placeholder" has no gesture here; the shared
 * fixture drives the pick seam with an empty value instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DropdownOptionSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val inputValues = mutableStateMapOf<String, Any>()

    @Before
    fun render() {
        SelectedOptionStore.resetForTesting()
        val blocks = OnboardingConfigParser.parseStepForTest(
            mapOf(
                "id" to "s", "type" to "custom",
                "config" to mapOf(
                    "content_blocks" to listOf(
                        // No display_style: the dropdown is the default.
                        mapOf(
                            "id" to "sel_plan", "type" to "input_select", "field_id" to "plan",
                            "field_placeholder" to "Choose a plan",
                            "field_options" to listOf(
                                mapOf(
                                    "id" to "monthly", "value" to "monthly", "label" to "Monthly",
                                    "sheet_blocks" to listOf(
                                        mapOf("id" to "sh_text", "type" to "text", "text" to "Billed every month"),
                                        mapOf("id" to "sh_done", "type" to "button", "text" to "Done", "action" to "dismiss"),
                                    ),
                                ),
                                mapOf("id" to "yearly", "value" to "yearly", "label" to "Yearly"),
                                // An option as a repeat generates it (`<template>__<n>`).
                                mapOf(
                                    "id" to "room_tpl__1", "value" to "r_b", "label" to "Loft",
                                    "sheet_blocks" to listOf(mapOf("id" to "sh_room", "type" to "text", "text" to "About this room")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )!!.config.content_blocks!!.toList()
        compose.setContent {
            ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = inputValues)
        }
        compose.waitForIdle()
    }

    @After
    fun reset() {
        SelectedOptionStore.resetForTesting()
    }

    /** The dropdown's own button (its label and the ▼ merge into one node). */
    private fun openMenu() {
        compose.onNodeWithText("▼").performClick()
        compose.waitForIdle()
    }

    /** The row titled [label] in the open menu (the button can show the same label). */
    private fun menuRow(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup()))

    private fun menuOpen(): Boolean =
        compose.onAllNodes(hasText("Yearly") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /**
     * Picking an option that owns a sheet records the answer AT ONCE and opens the sheet only once the
     * menu has finished closing — never on top of it. The clock is held after the click, so the frame
     * in which the menu is still animating closed can be inspected.
     * RED before the fix: the dropdown's click wrote the answer and never opened a sheet.
     */
    @Test
    fun pickingASheetOptionOpensItsSheetAfterTheMenuHasClosed() {
        assertFalse("control: no sheet before a pick", shows("Billed every month"))
        openMenu()
        assertTrue("the menu is open", menuOpen())

        compose.mainClock.autoAdvance = false
        menuRow("Monthly").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the choice is recorded first", "monthly", inputValues["plan"])
        assertTrue("the menu is still animating closed", menuOpen())
        assertFalse("the sheet does not open on top of the closing menu", shows("Billed every month"))

        compose.mainClock.autoAdvance = true
        settle()
        assertFalse("the menu has closed", menuOpen())
        assertTrue("the picked option's sheet opened", shows("Billed every month"))
        assertEquals("the sheet only presents: the answer is unchanged", "monthly", inputValues["plan"])
    }

    /** An option without `sheet_blocks` records its answer and opens nothing. */
    @Test
    fun pickingAnOptionWithoutASheetOpensNothing() {
        openMenu()
        menuRow("Yearly").performClick()
        settle()
        assertEquals("yearly", inputValues["plan"])
        assertFalse("the menu has closed", menuOpen())
        assertFalse("an option with no sheet opens nothing", shows("Billed every month"))
        assertFalse(shows("About this room"))
    }

    /** Picking the option that is already selected re-opens its sheet, as a re-tap does in the other styles. */
    @Test
    fun rePickingTheSameOptionReopensItsSheet() {
        openMenu()
        menuRow("Monthly").performClick()
        settle()
        assertTrue("the first pick opens the sheet", shows("Billed every month"))

        compose.onNodeWithText("Done").performClick()
        settle()
        assertFalse("the sheet's Done closed it", shows("Billed every month"))

        openMenu()
        menuRow("Monthly").performClick()
        settle()
        assertTrue("picking it again re-opens the sheet", shows("Billed every month"))
    }

    /** An option a repeat generated (id `<template>__<n>`) opens its own sheet. */
    @Test
    fun aGeneratedOptionOpensItsSheet() {
        openMenu()
        menuRow("Loft").performClick()
        settle()
        assertEquals("r_b", inputValues["plan"])
        assertTrue("the generated option's sheet opened", shows("About this room"))
    }
}
