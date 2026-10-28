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
 *                               `GoogleCreditSheetContext.shows`, the call the sheet makes.
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
        val answers = GoogleCredit.Answers(accumulated = accumulated, stepInputs = stepInputs, sessionStore = store)

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

        (action["sheet"] as? Map<String, Any?>)?.let { sheet ->
            val blockId = sheet["block_id"] as? String
            val optionId = sheet["option_id"] as? String
            val inputs = (sheet["inputs"] as? Map<String, Any?>).orEmpty()
            val context = GoogleCreditSheetContext(
                sources = { GoogleCredit.stepSources(step, override, fieldConfigOverrides, fieldOptionsOverrides) },
                answers = answers,
            )
            // The presented option, as the sheet receives it: from the typed blocks.
            val presented = step.config.content_blocks.orEmpty()
                .firstOrNull { it.id == blockId }?.field_options.orEmpty()
                .firstOrNull { (it.id ?: it.value) == optionId }?.sheet_blocks.orEmpty()
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
