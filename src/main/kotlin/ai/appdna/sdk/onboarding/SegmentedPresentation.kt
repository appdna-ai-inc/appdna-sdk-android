package ai.appdna.sdk.onboarding

/*
 * #1013 — WHETHER A `Segmented` INPUT DRAWS A SEGMENTED CONTROL, as one rule both platforms call.
 *
 * The console has shown a six-option "Display Style" Select for `input_select` AND `input_segmented`
 * since both existed (StepContentEditor.tsx:7901). Only the Select ever read it: iOS drew
 * `.pickerStyle(.segmented)` unconditionally and this platform drew a fixed equal-weight Row, so an
 * author who chose "Bubble / Chips" on a Segmented element saw no change on either device — the
 * defect #1013 reports, and the reason it reads as "the element does not react".
 *
 * 🔴 Withdrawing the control was the wrong remedy, and a measurement is what settled it: ONE
 * production block carries `display_style` on a Segmented input and the value it carries is
 * `bubble` — an author asking, in the only way the console offered, for the pills this issue is
 * about. Honouring the key gives them what they authored; removing it would have deleted the
 * request and left the complaint intact.
 *
 * So a Segmented input with a SELECT presentation authored renders through the Select, which already
 * draws all seven. Absent, empty or `segmented` keeps the segmented control, which is what all
 * twenty-six other stored Segmented blocks get and what every one of them drew before.
 */
object SegmentedPresentation {
    /** The presentations `input_select` draws. `display_style` is its key, and a Segmented block
     *  carrying one of these was authored by someone who wanted that presentation. */
    @JvmField
    val SELECT_STYLES: Set<String> = setOf(
        "dropdown", "stacked", "grid", "image_tiles", "bubble", "bubble_scroll", "list",
    )

    /** `true` → draw the segmented control: the default, and what every Segmented block drew before. */
    @JvmStatic
    fun drawsSegmentedControl(displayStyle: String?): Boolean {
        val raw = displayStyle?.trim()?.lowercase()
        if (raw.isNullOrEmpty()) return true
        // `segmented` names the control itself, so the console can offer a way BACK to it.
        if (raw == "segmented") return true
        // An unknown value is not an instruction: it keeps the control rather than falling through
        // to the Select's own `else` arm and silently becoming a dropdown.
        return raw !in SELECT_STYLES
    }
}
