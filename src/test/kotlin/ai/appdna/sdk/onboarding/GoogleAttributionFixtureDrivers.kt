package ai.appdna.sdk.onboarding

/**
 * Android drivers for Google's attribution, dispatched from `SharedFixtureTest`. Every value they
 * return is produced by REAL SDK code:
 *
 *   location_field_attribution  REAL: `LocationAnswer.carriesPlaceId`, the one rule both location
 *                               composables read to keep Google's logo beside a field.
 *   onboarding_google_credit    REAL: the step parsed by the SDK's own `OnboardingConfigParser` (its
 *                               published source and id-stamped raw blocks captured as for a published
 *                               flow), the override's options through `StepConfigOverride.decodeFieldOptions`
 *                               (the decoder every bridge uses), then `GoogleCredit.shows(step, …)` — the
 *                               call `OnboardingStepView` makes — and, for an option sheet,
 *                               `GoogleCreditSheetContext.shows`, the call the sheet makes, handed the
 *                               option's sheet blocks as the REAL pipeline resolved them.
 *
 * The dropdown's logo and the field's attribution through a tap are asserted by the
 * `location_resolve_request` kind (`list_google_logo` / `field_google_attribution` observables).
 *
 * The runner converts the fixture's JSON to plain maps and lists (JSON null → null) before calling.
 */
internal object GoogleAttributionFixtureDrivers {

    /** `action.cases[] = {name, stored_answer}` → `[{name, google_attribution}]`. */
    fun locationFieldAttribution(cases: List<Map<String, Any?>>): List<Map<String, Any?>> =
        cases.map { c ->
            val name = c["name"] as? String ?: throw AssertionError("a case needs a name")
            check(c.containsKey("stored_answer")) { "case $name needs stored_answer" }
            linkedMapOf("name" to name, "google_attribution" to LocationAnswer.carriesPlaceId(c["stored_answer"]))
        }

    /**
     * `action.cases[] = {name, step, block_id?}` → `[{name, backdrop, variant_light}]`: the step parsed
     * by the SDK's own parser, then `GoogleMapsAttribution.backdrop(ofStep, type)` — what the step
     * container provides — and, for a block, `backdrop(ofBlock, behind)` applied at every block from the
     * step down to it, as each block's wrapper applies it.
     */
    @Suppress("UNCHECKED_CAST")
    fun googleAttributionBackdrop(cases: List<Map<String, Any?>>): List<Map<String, Any?>> {
        fun chain(id: String, blocks: List<ContentBlock>): List<ContentBlock>? {
            for (b in blocks) {
                if (b.id == id) return listOf(b)
                chain(id, b.children.orEmpty() + b.stack_children.orEmpty())?.let { return listOf(b) + it }
            }
            return null
        }
        return cases.map { c ->
            val name = c["name"] as? String ?: throw AssertionError("a case needs a name")
            val step = OnboardingConfigParser.parseStepForTest(stripNulls(c["step"]) as Map<String, Any>)
                ?: throw AssertionError("$name: the SDK's parser refused the step")
            var backdrop = GoogleMapsAttribution.backdrop(ofStep = step.config, type = step.type)
            (c["block_id"] as? String)?.let { blockId ->
                val path = chain(blockId, step.config.content_blocks.orEmpty())
                    ?: throw AssertionError("$name: no block $blockId")
                for (b in path) backdrop = GoogleMapsAttribution.backdrop(ofBlock = b, behind = backdrop)
            }
            val described = when (val b = backdrop) {
                is GoogleMapsAttribution.Backdrop.Plain -> b.hex?.let { "plain:$it" } ?: "plain"
                GoogleMapsAttribution.Backdrop.Busy -> "busy"
            }
            linkedMapOf(
                "name" to name,
                "backdrop" to described,
                "variant_light" to GoogleMapsAttribution.variant(backdrop, darkAppearance = false).name.lowercase(),
            )
        }
    }

    /**
     * `setup.config.step` = one onboarding step as published; `setup.session_data` = {responses (the
     * flow's accumulated answers), session_store (the session store's onboarding responses), step (the
     * step's inputs)}. `action`: override {title, subtitle, cta_text, field_defaults, field_options},
     * field_config_overrides, field_options_overrides (the interaction patches), sheet {block_id,
     * option_id, inputs}. Returns `shows_credit`, and `sheet_credit` when the action names a sheet.
     */
    @Suppress("UNCHECKED_CAST")
    fun onboardingGoogleCredit(
        stepJson: Map<String, Any?>,
        session: Map<String, Any?>,
        action: Map<String, Any?>,
    ): Map<String, Any?> {
        val step = OnboardingConfigParser.parseStepForTest(stripNulls(stepJson) as Map<String, Any>)
            ?: throw AssertionError("the SDK's parser refused setup.config.step")
        val accumulated = (session["responses"] as? Map<String, Any?>).orEmpty()
        val stepInputs = (session["step"] as? Map<String, Any?>).orEmpty()
        val store = (session["session_store"] as? Map<String, Any?>).orEmpty()
        // `action.select = {step, block_id, option_id}`: an EARLIER step, resolved through the REAL
        // pipeline with the same answers, and the option the user picked there, recorded by the REAL
        // `SelectedOptionStore` — the `selected` root this step reads.
        var selected: Map<String, Any?> = emptyMap()
        var selectedLabel: String? = null
        (action["select"] as? Map<String, Any?>)?.let { pick ->
            val pickStep = OnboardingConfigParser.parseStepForTest(stripNulls(pick["step"]) as Map<String, Any>)
                ?: throw AssertionError("the SDK's parser refused action.select.step")
            val resolvedPick = HostDataStepPipeline(pickStep, legacyContext = { null }).resolve(
                HostDataInputs(responses = accumulated, stepInputs = emptyMap()),
            )
            val block = resolvedPick.blocks.firstOrNull { it.id == pick["block_id"] }
                ?: throw AssertionError("the picked block is not on the resolved step")
            val option = block.field_options.orEmpty().firstOrNull { (it.id ?: it.value) == pick["option_id"] }
                ?: throw AssertionError("the picked option is not on the resolved step")
            SelectedOptionStore.resetForTesting()
            try {
                SelectedOptionStore.record(block.field_id ?: block.id, option)
                selected = SelectedOptionStore.snapshot()
            } finally {
                SelectedOptionStore.resetForTesting()
            }
            selectedLabel = option.label
        }
        val answers = GoogleCredit.Answers(
            accumulated = accumulated, stepInputs = stepInputs, sessionStore = store, selected = selected,
        )

        val override = (action["override"] as? Map<String, Any?>)?.let { o ->
            StepConfigOverride(
                fieldDefaults = (o["field_defaults"] as? Map<String, Any?>)?.let { stripNulls(it) as Map<String, Any> },
                title = o["title"] as? String,
                subtitle = o["subtitle"] as? String,
                ctaText = o["cta_text"] as? String,
                fieldOptions = StepConfigOverride.decodeFieldOptions(stripNulls(o["field_options"])),
            )
        }
        val fieldConfigOverrides = ((action["field_config_overrides"] as? Map<String, Any?>).orEmpty())
            .mapValues { (_, v) -> stripNulls(v) as Map<String, Any> }
        val fieldOptionsOverrides = StepConfigOverride.decodeFieldOptions(stripNulls(action["field_options_overrides"]))
            .orEmpty()

        val out = linkedMapOf<String, Any?>(
            "shows_credit" to GoogleCredit.shows(step, override, fieldConfigOverrides, fieldOptionsOverrides, answers),
        )
        if (action.containsKey("select")) out["selected_label"] = selectedLabel

        // The step as the REAL pipeline resolves it (raw pass → decode → override → interaction
        // layering) — what the step draws, and what it stores.
        val resolveStep = {
            HostDataStepPipeline(step, legacyContext = { null }).resolve(
                HostDataInputs(
                    responses = accumulated,
                    stepInputs = stepInputs,
                    stepOverride = override,
                    fieldConfigOverrides = fieldConfigOverrides,
                    fieldOptionsOverrides = fieldOptionsOverrides,
                ),
            )
        }

        // `action.read[] = {name, block_id, option_id?, key}`: one value of the resolved step — an
        // option's `label` / `value`, or a block's `text` / `action_value` / `default_value` (its
        // `field_config.default_value`). Absent → null.
        (action["read"] as? List<Map<String, Any?>>)?.let { reads ->
            val blocks = resolveStep().blocks
            out["read"] = reads.associate { r ->
                val name = r["name"] as? String ?: throw AssertionError("a read needs a name")
                val key = r["key"] as? String ?: throw AssertionError("read $name needs a key")
                val block = blocks.firstOrNull { it.id == r["block_id"] }
                val optionId = r["option_id"] as? String
                val value: Any? = if (optionId != null) {
                    val option = block?.field_options.orEmpty().firstOrNull { (it.id ?: it.value) == optionId }
                    when (key) {
                        "label" -> option?.label
                        "value" -> option?.value
                        else -> throw AssertionError("read $name: unknown option key $key")
                    }
                } else {
                    when (key) {
                        "text" -> block?.text
                        "action_value" -> block?.action_value
                        "default_value" -> block?.field_config?.get("default_value")
                        else -> throw AssertionError("read $name: unknown block key $key")
                    }
                }
                name to value
            }
        }

        (action["sheet"] as? Map<String, Any?>)?.let { sheet ->
            val blockId = sheet["block_id"] as? String
            val optionId = sheet["option_id"] as? String
            val inputs = (sheet["inputs"] as? Map<String, Any?>).orEmpty()
            val context = GoogleCreditSheetContext(
                sources = { GoogleCredit.stepSources(step, override, fieldConfigOverrides, fieldOptionsOverrides) },
                answers = answers,
            )
            // The presented option, as the sheet receives it in production: from the step the REAL
            // pipeline resolved (raw pass → decode → override → interaction layering), so its sheet
            // blocks have every non-`step` token substituted and a `repeat` has generated its options.
            val resolved = resolveStep()
            val option = resolved.blocks.firstOrNull { it.id == blockId }?.field_options.orEmpty()
                .firstOrNull { (it.id ?: it.value) == optionId }
                ?: throw AssertionError("the resolved step has no option $optionId on $blockId")
            val presented = option.sheet_blocks.orEmpty()
            out["presented_sheet_text"] = presented.mapNotNull { it.text }
            out["sheet_credit"] = context.shows(blockId, optionId, presented, inputs)
        }
        return out
    }

    /** JSON nulls dropped, as the SDK's own parsers expect (`Map<String, Any>`). */
    private fun stripNulls(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any>().also { out ->
            for ((k, value) in v) if (value != null) out[k.toString()] = stripNulls(value)!!
        }
        is List<*> -> v.mapNotNull { stripNulls(it) }
        else -> v
    }
}
