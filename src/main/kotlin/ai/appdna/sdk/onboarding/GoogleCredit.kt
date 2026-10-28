package ai.appdna.sdk.onboarding

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether an onboarding step shows a Google answer's content in its text, and so must draw Google's
 * attribution in its own container.
 *
 * Google requires attribution "within the same visual container" wherever its content is shown
 * without a Google map. On an onboarding step, several resolvers substitute an answer into text — the
 * raw host-data pass for content blocks, the TemplateEngine behind `loc()` and every legacy view's
 * `interpolated()`, and the view-level block pass — and none of them has view context to report a
 * substitution up from. So the step's container decides BEFORE it renders, from the step's own data:
 * a pure function of the step's SOURCE (never the substituted copy, whose raw-resolved blocks no longer
 * hold tokens) and of the answers each root reads. `GoogleCredit` in `GoogleCredit.swift` is the
 * Swift twin, and the `onboarding_google_credit` shared fixtures pin the pair.
 *
 * The walk is generic, not a field list, so a field added to a step later is covered:
 * - every string's `{{…}}` tokens (the same scanner the raw pass uses);
 * - every string under a block's `bindings` object, as a bare path (the raw pass resolves those);
 * - visibility conditions name their variable without braces and outside `bindings`, so they never set
 *   it — a condition decides whether something shows, it shows nothing itself.
 *
 * A path credits when, under a root that reads onboarding answers, it reaches an answer carrying
 * `place_id` ([LocationAnswer.carriesPlaceId]), a key inside one, or a value holding one.
 *
 * An option's `sheet_blocks` are a modal ABOVE the step, so the step's walk skips them and each sheet
 * walks its own, drawing the credit inside the sheet; a sheet resolves its `step` root against its own
 * inputs, as the sheet renderer does.
 *
 * The step's source is the step as published ([OnboardingStep.rawSource], with its id-stamped raw
 * blocks). What a host or an interaction hands over as TYPED values (an override's options, a
 * replacement option list) has no published source; each typed option or block is walked through its
 * data-class rendering, which lists every property it holds, so every string in it is scanned too.
 */
internal object GoogleCredit {

    /** The roots that read onboarding answers. */
    val ROOTS: Set<String> = setOf("responses", "step", "input", "onboarding")

    /** The answers each root reads. */
    class Answers private constructor(
        /**
         * `responses`: the flow's accumulated answers AND the session store's onboarding responses — a
         * block that is not raw-resolved interpolates through the TemplateEngine, which reads the
         * latter. That store is replaced only when a step completes, so on the first step of a
         * re-presented flow it can still hold the previous run's answer; it is walked all the same.
         */
        val responses: List<Map<String, Any?>>,
        /** `step`: the current step's inputs (a sheet's own inputs, for a sheet). */
        val step: Map<String, Any?>,
        /** `input` / `onboarding`, and every legacy view's `interpolated()`: the session store. */
        val onboarding: Map<String, Any?>,
    ) {
        constructor(
            accumulated: Map<String, Any?>,
            stepInputs: Map<String, Any?>,
            sessionStore: Map<String, Any?>,
        ) : this(listOf(accumulated, sessionStore), stepInputs, sessionStore)

        /** The same answers, with the `step` root reading [inputs] (an option sheet's own). */
        fun withStepInputs(inputs: Map<String, Any?>): Answers = Answers(responses, inputs, onboarding)

        /** Whether any answer a root can read is a Google one. Nothing can credit otherwise. */
        val holdAGoogleAnswer: Boolean
            get() = responses.any { holdsGoogleAnswer(it) } || holdsGoogleAnswer(step) || holdsGoogleAnswer(onboarding)
    }

    // ---- The step -------------------------------------------------------------------------------

    /**
     * The step's source, as the walk reads it: the step as published (and its id-stamped raw blocks,
     * so an option sheet is filed under the id the renderer knows it by), the host's override, and the
     * interaction patches the step layers on (`fieldConfigOverrides` / `fieldOptionsOverrides`).
     */
    fun stepSources(
        step: OnboardingStep,
        override: StepConfigOverride?,
        fieldConfigOverrides: Map<String, Map<String, Any>>,
        fieldOptionsOverrides: Map<String, List<InputOption>>,
    ): List<Any> {
        val sources = mutableListOf<Any>()
        val published = step.rawSource?.map
        if (published != null) {
            sources.add(published)
            step.rawContentBlocks?.blocks?.let { sources.add(it) }
        } else {
            // A step built in code has no published source: its typed config instead.
            sources.add(typedStepSource(step))
        }
        override?.let { sources.add(overrideSource(it)) }
        if (fieldConfigOverrides.isNotEmpty()) sources.add(fieldConfigOverrides)
        if (fieldOptionsOverrides.isNotEmpty()) {
            // Keyed by block id, as the step keys them; kept as `{id, field_options}` objects so the
            // sheet index below files each option's sheet under its block.
            sources.add(fieldOptionsOverrides.map { (blockId, options) ->
                mapOf("id" to blockId, "field_options" to options.map(::optionSource))
            })
        }
        return sources
    }

    /** The step's credit: its own walk, `sheet_blocks` skipped. */
    fun shows(sources: List<Any>, answers: Answers): Boolean {
        if (!answers.holdAGoogleAnswer) return false
        return sources.any { walk(it, answers) }
    }

    /** The step's credit from its parts — what the step container calls. */
    fun shows(
        step: OnboardingStep,
        override: StepConfigOverride?,
        fieldConfigOverrides: Map<String, Map<String, Any>>,
        fieldOptionsOverrides: Map<String, List<InputOption>>,
        answers: Answers,
    ): Boolean {
        if (!answers.holdAGoogleAnswer) return false
        return shows(stepSources(step, override, fieldConfigOverrides, fieldOptionsOverrides), answers)
    }

    // ---- Option sheets --------------------------------------------------------------------------

    /**
     * Every option sheet in the sources, by [sheetKey]: the source of each, walked on its own when that
     * sheet opens. A key seen twice (the authored option and a host's replacement with the same id)
     * keeps both sources, so either one's reference credits.
     */
    fun sheetSources(sources: List<Any>): Map<String, List<Any?>> {
        val out = LinkedHashMap<String, MutableList<Any?>>()
        for (s in sources) indexSheets(s, out)
        return out
    }

    fun sheetKey(blockId: String, optionId: String): String = "$blockId\u001F$optionId"

    /** A sheet's credit: its own blocks, its `step` root its own inputs, every other root the step's. */
    fun sheetShows(sheetBlocks: List<Any?>, sheetInputs: Map<String, Any?>, answers: Answers): Boolean {
        val own = answers.withStepInputs(sheetInputs)
        if (!own.holdAGoogleAnswer) return false
        return sheetBlocks.any { it != null && walk(it, own) }
    }

    // ---- The walk -------------------------------------------------------------------------------

    /** Every string in [value], `sheet_blocks` skipped. Exposed so the fixture runner drives this. */
    fun walk(value: Any, answers: Answers): Boolean = when (value) {
        is String -> tokensCredit(value, answers)
        is Map<*, *> -> value.entries.any { (key, child) ->
            when {
                child == null -> false
                key == "sheet_blocks" -> false
                key == "bindings" && bindingsCredit(child, answers) -> true
                else -> walk(child, answers)
            }
        }
        is List<*> -> value.any { it != null && walk(it, answers) }
        else -> false
    }

    /** The `{{…}}` tokens of one string. */
    private fun tokensCredit(s: String, answers: Answers): Boolean {
        if (!s.contains("{{")) return false
        return HostDataResolver.extractTemplateTokens(s).any { pathCredits(it.path, answers) }
    }

    /** Every string under a `bindings` object is a bare path. */
    private fun bindingsCredit(value: Any, answers: Answers): Boolean = when (value) {
        is String -> pathCredits(value.trim(), answers)
        is Map<*, *> -> value.values.any { it != null && bindingsCredit(it, answers) }
        is List<*> -> value.any { it != null && bindingsCredit(it, answers) }
        else -> false
    }

    /**
     * Whether one path, under an onboarding-answer root, reaches a Google answer, a key inside one, or
     * a value holding one.
     */
    fun pathCredits(path: String, answers: Answers): Boolean {
        val segments = path.split(".")
        val root = segments.first()
        if (root !in ROOTS) return false
        val rest = segments.drop(1)
        return when (root) {
            "responses" -> answers.responses.any { reaches(rest, it) }
            "step" -> reaches(rest, answers.step)
            "onboarding" -> reaches(rest, answers.onboarding)
            "input" -> {
                // `{{input.<field>}}` searches every step's answers for the field.
                val field = rest.firstOrNull() ?: return holdsGoogleAnswer(answers.onboarding)
                answers.onboarding.values.any { stepAnswers ->
                    val value = (stepAnswers as? Map<*, *>)?.get(field) ?: return@any false
                    reaches(rest.drop(1), value)
                }
            }
            else -> false
        }
    }

    /**
     * Follows [segments] into [value]. Credits as soon as the walk stands on a Google answer (the rest
     * of the path is a key inside it), or when the value it ends on holds one.
     */
    private fun reaches(segments: List<String>, value: Any): Boolean {
        var current: Any = value
        for (segment in segments) {
            if (LocationAnswer.carriesPlaceId(current)) return true
            current = when (val c = current) {
                is Map<*, *> -> c[segment] ?: return false
                is List<*> -> segment.toIntOrNull()?.let { c.getOrNull(it) } ?: return false
                else -> return false
            }
        }
        return holdsGoogleAnswer(current)
    }

    /** Whether [value] is, or holds at any depth, a Google answer. */
    fun holdsGoogleAnswer(value: Any?): Boolean = when {
        LocationAnswer.carriesPlaceId(value) -> true
        value is Map<*, *> -> value.values.any { holdsGoogleAnswer(it) }
        value is List<*> -> value.any { holdsGoogleAnswer(it) }
        else -> false
    }

    // ---- Typed values ---------------------------------------------------------------------------

    /** The host's override: its text, its field defaults, and its options filed under their blocks. */
    private fun overrideSource(o: StepConfigOverride): Map<String, Any?> = mapOf(
        "title" to o.title,
        "subtitle" to o.subtitle,
        "cta_text" to o.ctaText,
        "field_defaults" to o.fieldDefaults,
        "content_blocks" to o.fieldOptions?.map { (blockId, options) ->
            mapOf("id" to blockId, "field_options" to options.map(::optionSource))
        },
    )

    /**
     * A typed option: every property but its sheet as one string, its id as the sheet index reads it
     * (`id`, else `value`), and its sheet's blocks.
     */
    internal fun optionSource(o: InputOption): Map<String, Any?> = mapOf(
        "id" to (o.id ?: o.value),
        "text" to o.copy(sheet_blocks = null).toString(),
        "sheet_blocks" to o.sheet_blocks?.map(::blockSource),
    )

    /**
     * A typed block: every property but its child blocks and options as one string, its `bindings`,
     * and its children and options walked the same way.
     */
    internal fun blockSource(b: ContentBlock): Map<String, Any?> = mapOf(
        "id" to b.id,
        "text" to b.copy(children = null, stack_children = null, field_options = null, bindings = null).toString(),
        "bindings" to b.bindings,
        "children" to b.children?.map(::blockSource),
        "stack_children" to b.stack_children?.map(::blockSource),
        "field_options" to b.field_options?.map(::optionSource),
    )

    private fun typedStepSource(step: OnboardingStep): Map<String, Any?> = mapOf(
        "config" to step.config.copy(content_blocks = null).toString(),
        "content_blocks" to step.config.content_blocks?.map(::blockSource),
    )

    private fun indexSheets(value: Any?, out: MutableMap<String, MutableList<Any?>>) {
        when (value) {
            is Map<*, *> -> {
                val blockId = value["id"] as? String
                val options = value["field_options"] as? List<*>
                if (blockId != null && options != null) {
                    for (option in options) {
                        val o = option as? Map<*, *> ?: continue
                        val sheet = (o["sheet_blocks"] as? List<*>)?.takeIf { it.isNotEmpty() } ?: continue
                        // An option reads its id as `id`, else `value`.
                        val optionId = (o["id"] as? String) ?: (o["value"] as? String) ?: ""
                        out.getOrPut(sheetKey(blockId, optionId)) { mutableListOf() }.addAll(sheet)
                    }
                }
                for (child in value.values) indexSheets(child, out)
            }
            is List<*> -> for (child in value) indexSheets(child, out)
        }
    }
}

/**
 * What an option sheet needs to decide its own credit. Handed down from the step to the option sheets
 * it can open; a sheet looks up its own source by block and option, and walks it with its own inputs.
 */
internal class GoogleCreditSheetContext(
    val sources: () -> List<Any>,
    val answers: GoogleCredit.Answers,
) {
    /**
     * The credit for the sheet of [optionId] on [blockId]. A sheet whose source is not found (an option
     * a `repeat` template generated) walks the presented blocks instead.
     */
    fun shows(
        blockId: String?,
        optionId: String?,
        presentedSheetBlocks: List<ContentBlock>,
        sheetInputs: Map<String, Any?>,
    ): Boolean {
        if (!answers.withStepInputs(sheetInputs).holdAGoogleAnswer) return false
        val index = GoogleCredit.sheetSources(sources())
        val source = if (blockId != null && optionId != null) index[GoogleCredit.sheetKey(blockId, optionId)] else null
        if (source != null) return GoogleCredit.sheetShows(source, sheetInputs, answers)
        return GoogleCredit.sheetShows(presentedSheetBlocks.map(GoogleCredit::blockSource), sheetInputs, answers)
    }
}

/** The onboarding step's sheet credit context; null outside an onboarding step. */
internal val LocalGoogleCreditSheetContext = compositionLocalOf<GoogleCreditSheetContext?> { null }
