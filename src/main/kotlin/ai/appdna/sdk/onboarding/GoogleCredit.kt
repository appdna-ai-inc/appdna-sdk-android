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
 * Only what can be DRAWN is walked, so a key or an entry nothing renders never credits — the same rule
 * as the Swift twin, which the `onboarding_google_credit` fixtures hold the pair to:
 * - the step's config as the SDK parses it (a key the parser drops is never drawn), walked through its
 *   data-class rendering, which lists every property it holds;
 * - each content block as published ([OnboardingStep.rawContentBlocks], id-stamped), because the raw
 *   host-data pass resolves the published block before it is decoded (`data_templates` render from
 *   keys the typed block does not hold);
 * - the config after the host's override is merged, as the step merges it: an override's options for
 *   a block the step does not hold are dropped;
 * - the interaction patches, for the blocks they are layered onto — the step's top-level blocks.
 *
 * The `item` root reads the element a `repeat` generated an option from. The raw pass expands a
 * `repeat` on a top-level `input_select` only, so the walk gives `item` a value only inside such a
 * block's template option (and that option's sheet): the elements of the array its `source` reaches
 * among the answers the source's root reads. A `source` under `hook_data` (or `user`, `session`,
 * `selected`) is host-supplied, which makes the host responsible for its attribution, and a block with
 * an Option Set never expands — so neither gives `item` anything to credit.
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
        /**
         * `item`: what it can stand on where the walk stands — empty outside a repeat's template option
         * ([repeatItems]).
         */
        val items: List<Any> = emptyList(),
    ) {
        constructor(
            accumulated: Map<String, Any?>,
            stepInputs: Map<String, Any?>,
            sessionStore: Map<String, Any?>,
        ) : this(listOf(accumulated, sessionStore), stepInputs, sessionStore)

        /** The same answers, with the `step` root reading [inputs] (an option sheet's own). */
        fun withStepInputs(inputs: Map<String, Any?>): Answers = Answers(responses, inputs, onboarding, items)

        /** The same answers, with the `item` root standing on [items]. */
        fun withItems(items: List<Any>): Answers = Answers(responses, step, onboarding, items)

        /** Whether any answer a root can read is a Google one. Nothing can credit otherwise. */
        val holdAGoogleAnswer: Boolean
            get() = responses.any { holdsGoogleAnswer(it) } || holdsGoogleAnswer(step) || holdsGoogleAnswer(onboarding)
    }

    // ---- The step -------------------------------------------------------------------------------

    /** The step's source, as the walk reads it (see the class comment for what is walked and why). */
    fun stepSources(
        step: OnboardingStep,
        override: StepConfigOverride?,
        fieldConfigOverrides: Map<String, Map<String, Any>>,
        fieldOptionsOverrides: Map<String, List<InputOption>>,
    ): List<Any> {
        val sources = mutableListOf<Any>()
        sources.add(typedConfigSource(step.config))
        step.rawContentBlocks?.blocks?.let { sources.add(it) }
        // Without an override the merged config IS the step's, already walked.
        val merged = override?.let { step.config.applyingOverride(it) }
        merged?.let { sources.add(typedConfigSource(it)) }
        // A patch reaches the screen only through a block the step draws.
        val drawn = (merged ?: step.config).content_blocks.orEmpty().map { it.id }.toSet()
        val patches = fieldConfigOverrides.filterKeys { it in drawn }
        if (patches.isNotEmpty()) sources.add(patches)
        val options = fieldOptionsOverrides.filterKeys { it in drawn }
        if (options.isNotEmpty()) {
            // Keyed by block id, as the step keys them; kept as `{id, field_options}` objects so the
            // sheet index below files each option's sheet under its block.
            sources.add(options.map { (blockId, opts) ->
                mapOf("id" to blockId, "field_options" to opts.map(::optionSource))
            })
        }
        return sources
    }

    /** The step's credit: its own walk, `sheet_blocks` skipped. */
    fun shows(sources: List<Any>, answers: Answers): Boolean {
        if (!answers.holdAGoogleAnswer) return false
        return sources.any { walkSource(it, answers) }
    }

    /**
     * One source. A source that is a list is the step's published top-level blocks (the options patch
     * is a list too, of `{id, field_options}` objects that never hold a `repeat`), and a top-level block
     * is the only one the raw pass expands a `repeat` on: its template option is walked with `item`
     * standing on what the repeat generates from.
     */
    private fun walkSource(source: Any, answers: Answers): Boolean {
        if (source !is List<*>) return walk(source, answers)
        return source.any { block ->
            if (block == null) return@any false
            val r = (block as? Map<*, *>)?.let(::repeatOf) ?: return@any walk(block, answers)
            if (walk(block.filterKeys { it != "field_options" }, answers)) return@any true
            val generated = answers.withItems(repeatItems(r.repeat, answers))
            r.options.withIndex().any { (i, option) ->
                option != null && walk(option, if (i == r.templateIndex) generated else answers)
            }
        }
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

    /** Every option sheet in the sources, and the `repeat` each top-level template option is expanded by. */
    class SheetIndex(
        /**
         * By [sheetKey]: the source of each sheet, walked on its own when that sheet opens. A key seen
         * twice (the authored option and a host's replacement with the same id) keeps both sources, so
         * either one's reference credits.
         */
        val sheets: Map<String, List<Any?>>,
        /** By the template option's [sheetKey]: the `repeat` that generates options from it. */
        val repeats: Map<String, Map<*, *>>,
    )

    /** The sheet index of the sources ([SheetIndex]). */
    fun sheetSources(sources: List<Any>): SheetIndex {
        val sheets = LinkedHashMap<String, MutableList<Any?>>()
        val repeats = LinkedHashMap<String, Map<*, *>>()
        for (s in sources) {
            indexSheets(s, sheets)
            // Top-level blocks only, as [walkSource] reads them.
            for (block in (s as? List<*>).orEmpty()) {
                val b = block as? Map<*, *> ?: continue
                val r = repeatOf(b) ?: continue
                val blockId = b["id"] as? String ?: continue
                repeats[sheetKey(blockId, r.templateId)] = r.repeat
            }
        }
        return SheetIndex(sheets, repeats)
    }

    /** A sheet's source, and what its `item` root stands on. */
    class Sheet(val blocks: List<Any?>, val items: List<Any>)

    fun sheetKey(blockId: String, optionId: String): String = "$blockId\u001F$optionId"

    /**
     * The source of the sheet [optionId] opens on [blockId], and what its `item` root stands on. An
     * option a `repeat` generated is `<template id>__<n>`, and it opens its TEMPLATE's sheet: the index
     * holds that one, filed under the template's id, as authored — and its `item` is element `n` of the
     * repeat's source, resolved against the STEP's [answers], where the raw pass resolves it.
     */
    fun sheetSource(index: SheetIndex, blockId: String, optionId: String, answers: Answers): Sheet? {
        index.sheets[sheetKey(blockId, optionId)]?.let { return Sheet(it, emptyList()) }
        val cut = optionId.lastIndexOf("__")
        if (cut < 0) return null
        val n = optionId.substring(cut + 2)
        if (n.isEmpty() || !n.all { it.isDigit() }) return null
        val template = sheetKey(blockId, optionId.substring(0, cut))
        val blocks = index.sheets[template] ?: return null
        val rep = index.repeats[template]
        val i = n.toIntOrNull()
        if (rep == null || i == null) return Sheet(blocks, emptyList())
        return Sheet(blocks, repeatItems(rep, answers, i))
    }

    /**
     * A sheet's credit: its own blocks, its `step` root its own inputs, its `item` root [items], every
     * other root the step's.
     */
    fun sheetShows(
        sheetBlocks: List<Any?>,
        sheetInputs: Map<String, Any?>,
        answers: Answers,
        items: List<Any> = emptyList(),
    ): Boolean {
        val own = answers.withStepInputs(sheetInputs).withItems(items)
        if (!own.holdAGoogleAnswer) return false
        return sheetBlocks.any { it != null && walk(it, own) }
    }

    // ---- Repeats --------------------------------------------------------------------------------

    private class Repeat(val repeat: Map<*, *>, val templateId: String, val templateIndex: Int, val options: List<*>)

    /**
     * A top-level block's `repeat`, when the raw pass expands one: an `input_select` with a
     * `field_config.repeat`, no Option Set, and an option whose `id` is the template's.
     */
    private fun repeatOf(block: Map<*, *>): Repeat? {
        if (block["type"] != "input_select") return null
        val fc = block["field_config"] as? Map<*, *> ?: return null
        val rep = fc["repeat"] as? Map<*, *> ?: return null
        val os = fc["option_set_id"]
        if (os is String && os.trim().isNotEmpty()) return null
        val tid = rep["template_option_id"] as? String ?: return null
        val options = block["field_options"] as? List<*> ?: return null
        val i = options.indexOfFirst { it is Map<*, *> && it["id"] == tid }
        if (i < 0) return null
        return Repeat(rep, tid, i, options)
    }

    /**
     * What `item` stands on in the options [rep] generates: each element, up to its `max`, of the array
     * its `source` reaches among the answers the source's root reads — only element [n] when [n] is
     * given. A source that passes through a Google answer stands `item` on that answer, since everything
     * it generates is a part of it. Only `responses` and `step` sources read onboarding answers; any
     * other root gives nothing.
     */
    fun repeatItems(rep: Map<*, *>, answers: Answers, n: Int? = null): List<Any> {
        val source = rep["source"] as? String ?: return emptyList()
        val segments = source.split(".")
        if (segments.size < 2) return emptyList()
        val roots: List<Any> = when (segments[0]) {
            "responses" -> answers.responses
            "step" -> listOf(answers.step)
            else -> return emptyList()
        }
        val max = repeatMax(rep["max"])
        val out = mutableListOf<Any>()
        for (root in roots) {
            var current: Any? = root
            var reached = true
            for (segment in segments.drop(1)) {
                // Standing on a Google answer: the rest of the source is a key inside it.
                if (LocationAnswer.carriesPlaceId(current)) { out.add(current!!); reached = false; break }
                val c = current
                current = when {
                    c is Map<*, *> && c[segment] != null -> c[segment]
                    c is List<*> && segment.isNotEmpty() && segment.all { it in '0'..'9' } ->
                        segment.toIntOrNull()?.let { c.getOrNull(it) }
                    else -> null
                }
                if (current == null) { reached = false; break }
            }
            val a = current as? List<*>
            if (!reached || a == null) continue
            val drawn = a.take(max)
            if (n != null) drawn.getOrNull(n)?.let { out.add(it) } else drawn.filterNotNullTo(out)
        }
        return out
    }

    /** A repeat's `max`, as the raw pass reads it: a positive whole number, capped; else the default. */
    private fun repeatMax(value: Any?): Int {
        val d = (value as? Number)?.toDouble() ?: return HostDataResolver.REPEAT_DEFAULT_MAX
        if (!d.isFinite() || d <= 0 || d != Math.floor(d)) return HostDataResolver.REPEAT_DEFAULT_MAX
        return minOf(d, HostDataResolver.REPEAT_HARD_MAX.toDouble()).toInt()
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
     * Whether one path, under an onboarding-answer root or the `item` a repeat generated from them,
     * reaches a Google answer, a key inside one, or a value holding one.
     */
    fun pathCredits(path: String, answers: Answers): Boolean {
        val segments = path.split(".")
        val root = segments.first()
        val rest = segments.drop(1)
        if (root == "item") return answers.items.any { reaches(rest, it) }
        if (root !in ROOTS) return false
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

    /** A typed config: every property but its blocks as one string, and its blocks walked as blocks. */
    private fun typedConfigSource(config: StepConfig): Map<String, Any?> = mapOf(
        "config" to config.copy(content_blocks = null).toString(),
        "content_blocks" to config.content_blocks?.map(::blockSource),
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
     * The credit for the sheet of [optionId] on [blockId], from the sheet's SOURCE: the presented
     * blocks of a raw-resolved sheet have already had every token but its `step` ones substituted, so
     * they no longer say what they show. An option a `repeat` generated finds its template's sheet, its
     * `item` root the element it was generated from. Only a sheet no source holds (an Option Set's
     * option) walks the blocks it presents.
     */
    fun shows(
        blockId: String?,
        optionId: String?,
        presentedSheetBlocks: List<ContentBlock>,
        sheetInputs: Map<String, Any?>,
    ): Boolean {
        if (!answers.withStepInputs(sheetInputs).holdAGoogleAnswer) return false
        val source = if (blockId != null && optionId != null) {
            GoogleCredit.sheetSource(GoogleCredit.sheetSources(sources()), blockId, optionId, answers)
        } else null
        if (source != null) return GoogleCredit.sheetShows(source.blocks, sheetInputs, answers, source.items)
        return GoogleCredit.sheetShows(presentedSheetBlocks.map(GoogleCredit::blockSource), sheetInputs, answers)
    }
}

/** The onboarding step's sheet credit context; null outside an onboarding step. */
internal val LocalGoogleCreditSheetContext = compositionLocalOf<GoogleCreditSheetContext?> { null }
