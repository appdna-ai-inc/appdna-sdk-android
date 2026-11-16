package ai.appdna.sdk.onboarding

import ai.appdna.sdk.Log
import ai.appdna.sdk.core.AgreementConsents
import androidx.compose.runtime.Immutable

/**
 * A sheet inside a step: declared on the step (`sheets`, beside `content_blocks`), opened by a button on
 * the step (`action: "open_sheet"`, `sheet_id`) or by the step's Continue when the first required answer it
 * is missing sits in the sheet. Mirror of iOS `StepSheets.swift`, pinned by the shared `step_sheet_*`
 * fixtures.
 *
 * The sheet's blocks are the STEP's: its inputs write the step's own answer store, so a consent ticked
 * in it is in the step's answers the moment it is ticked, and the step's required-field gate counts
 * its blocks. Closing it by a gesture is not a decline: what was ticked stays, nothing is written false,
 * and Continue stays gated until the required answers are given.
 */
@Immutable
data class StepSheet(
    val id: String,
    val content_blocks: List<ContentBlock>,
    /** `fit` (as tall as the content, up to `large`), `medium` (half the screen) or `large`. */
    val height: String = "fit",
    /** Whether a swipe, a scrim tap and back close it. */
    val dismissible: Boolean = true,
    val scrim_color: String = StepSheets.DEFAULT_SCRIM,
    /** Null draws the step's background colour. */
    val background_color: String? = null,
    val corner_radius: Double = 16.0,
    val drag_indicator: Boolean = true,
    /** Whether Continue opens it when the first missing required answer is in it. */
    val open_on_gate: Boolean = true,
)

object StepSheets {
    const val OPEN_ACTION = "open_sheet"
    const val CLOSE_ACTION = "close_sheet"
    const val OPENED_EVENT = "onboarding_sheet_opened"
    const val CLOSED_EVENT = "onboarding_sheet_closed"
    const val DEFAULT_SCRIM = "#00000066"
    /** Where the decoder folds a button's `sheet_id` (see `ContentBlock.sheetId`). */
    const val SHEET_ID_KEY = "sheet_id"

    /**
     * The step's sheets from the raw `sheets` list. A sheet with no id is dropped (the publisher refuses
     * it); a repeated id keeps the first. Null when there is nothing to draw.
     */
    fun parse(
        raw: List<*>?,
        path: String,
        decodeBlock: (Map<String, Any>, String) -> ContentBlock,
    ): List<StepSheet>? {
        if (raw.isNullOrEmpty()) return null
        val seen = mutableSetOf<String>()
        val out = mutableListOf<StepSheet>()
        raw.forEachIndexed { i, item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any> ?: return@forEachIndexed
            val id = (m["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            if (!seen.add(id)) return@forEachIndexed
            val blocks = (m["content_blocks"] as? List<*>).orEmpty().mapIndexedNotNull { j, b ->
                @Suppress("UNCHECKED_CAST")
                (b as? Map<String, Any>)?.let { decodeBlock(it, "$path[$i].content_blocks[$j]") }
            }
            out += StepSheet(
                id = id,
                content_blocks = blocks,
                height = (m["height"] as? String)?.takeIf { it == "fit" || it == "medium" || it == "large" } ?: "fit",
                dismissible = m["dismissible"] as? Boolean ?: true,
                scrim_color = (m["scrim_color"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_SCRIM,
                background_color = (m["background_color"] as? String)?.takeIf { it.isNotBlank() },
                corner_radius = (m["corner_radius"] as? Number)?.toDouble() ?: 16.0,
                drag_indicator = m["drag_indicator"] as? Boolean ?: true,
                open_on_gate = m["open_on_gate"] as? Boolean ?: true,
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** What the step's required-field gate reads: the step's blocks, then every sheet's, in order. */
    fun gateBlocks(stepBlocks: List<ContentBlock>, sheets: List<StepSheet>?): List<ContentBlock> =
        if (sheets.isNullOrEmpty()) stepBlocks else stepBlocks + sheets.flatMap { it.content_blocks }

    /**
     * Seeds the sheets' agreement defaults into the step's answers as the step first appears, as an on-step
     * agreement seeds itself when drawn: each consent's `default_checked` under `<field_id>.<consent_id>`, a
     * single-shape agreement's under its field id. A value already there (a restored answer) is kept.
     */
    fun seedDefaults(sheets: List<StepSheet>?, inputValues: MutableMap<String, Any>) {
        for (sheet in sheets.orEmpty()) {
            for (block in sheet.content_blocks) {
                if (block.type != "agreement") continue
                val fieldId = block.field_id ?: block.id
                val consents = AgreementConsents.parse(block.field_config?.get("consents"))
                if (consents.isEmpty()) {
                    if (inputValues[fieldId] !is Boolean) {
                        inputValues[fieldId] = (block.field_config?.get("default_checked") as? Boolean) ?: false
                    }
                } else {
                    for (c in consents) {
                        val key = AgreementConsents.key(fieldId, c.id)
                        if (inputValues[key] !is Boolean) inputValues[key] = c.defaultChecked
                    }
                }
            }
        }
    }
}

/** What the step does when its gate refuses an action (see [StepSheetController.onGateRefused]). */
enum class StepSheetGateOutcome { OPENED_SHEET, SHOW_TOAST }

/** What a button inside the open sheet does (see [StepSheetController.onSheetAction]). */
enum class StepSheetActionOutcome { HANDLED, PASS_TO_STEP }

/**
 * The per-step state of its sheets: which one is open, how it was opened, and the action the gate refused
 * (re-run by the sheet's `next`). Pure: the renderer and the fixture runner drive the same instance.
 */
class StepSheetController(
    val sheets: List<StepSheet>,
    private val flowId: String,
    private val stepId: String,
    private val effects: Effects,
) {
    interface Effects {
        fun track(name: String, properties: Map<String, Any>)
        /** The sheet now shown, or null when none is. */
        fun present(sheet: StepSheet?)
        /** Re-run the action the gate refused (through the step's own dispatcher, which gates again). */
        fun rerun(action: String)
        /** The step's skip. */
        fun skip()
    }

    var openSheet: StepSheet? = null
        private set
    private var pendingAction: String? = null

    fun sheet(id: String?): StepSheet? = id?.let { wanted -> sheets.firstOrNull { it.id == wanted } }

    /**
     * Opens the sheet [id] (`via` = `button` | `gate`). One sheet at a time: opening while one is up
     * replaces it. An id not on the step is a no-op with a debug log (the publisher refuses it).
     */
    fun open(id: String?, via: String): Boolean {
        val target = sheet(id)
        if (target == null) {
            Log.debug("Onboarding: open_sheet \"${id ?: ""}\" is not a sheet on step $stepId; ignored")
            return false
        }
        if (via != "gate") pendingAction = null
        openSheet = target
        effects.track(StepSheets.OPENED_EVENT, mapOf("flow_id" to flowId, "step_id" to stepId, "sheet_id" to target.id, "via" to via))
        effects.present(target)
        return true
    }

    /**
     * The step's gate refused [action]. When the step's own blocks pass and the first sheet whose blocks do
     * not is `open_on_gate`, that sheet opens (no toast) and [action] is remembered for its `next`.
     * [passes] is the step's real gate over one block list.
     */
    fun onGateRefused(
        action: String,
        stepBlocks: List<ContentBlock>,
        passes: (List<ContentBlock>) -> Boolean,
    ): StepSheetGateOutcome {
        if (sheets.isEmpty() || !passes(stepBlocks)) return StepSheetGateOutcome.SHOW_TOAST
        val missing = sheets.firstOrNull { !passes(it.content_blocks) } ?: return StepSheetGateOutcome.SHOW_TOAST
        if (!missing.open_on_gate) return StepSheetGateOutcome.SHOW_TOAST
        open(missing.id, "gate")
        pendingAction = action
        return StepSheetGateOutcome.OPENED_SHEET
    }

    /** A button inside the open sheet, with its decoded action and value. */
    fun onSheetAction(action: String, value: String?): StepSheetActionOutcome {
        if (openSheet == null) return StepSheetActionOutcome.PASS_TO_STEP
        return when (action) {
            "next", "continue" -> {
                val pending = pendingAction
                close("next")
                if (pending != null) effects.rerun(pending)
                StepSheetActionOutcome.HANDLED
            }
            "dismiss", StepSheets.CLOSE_ACTION -> { close("dismiss"); StepSheetActionOutcome.HANDLED }
            "skip" -> { close("skip"); effects.skip(); StepSheetActionOutcome.HANDLED }
            StepSheets.OPEN_ACTION -> { open(value, "button"); StepSheetActionOutcome.HANDLED }
            else -> StepSheetActionOutcome.PASS_TO_STEP
        }
    }

    /**
     * A swipe, a scrim tap or back ([via]). Refused (false) when the sheet is not dismissible. Not a
     * decline: nothing is written, nothing is re-run.
     */
    fun onDismissGesture(via: String): Boolean {
        val s = openSheet ?: return false
        if (!s.dismissible) return false
        close(via)
        return true
    }

    private fun close(via: String) {
        val s = openSheet ?: return
        openSheet = null
        pendingAction = null
        effects.track(StepSheets.CLOSED_EVENT, mapOf("flow_id" to flowId, "step_id" to stepId, "sheet_id" to s.id, "via" to via))
        effects.present(null)
    }
}

/** An `open_sheet` button's target (folded into `field_config` by the decoder). */
internal val ContentBlock.sheetId: String? get() = field_config?.get(StepSheets.SHEET_ID_KEY) as? String
