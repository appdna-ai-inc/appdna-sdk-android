package ai.appdna.sdk.onboarding

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.interpolated
import ai.appdna.sdk.core.StyleEngine
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.*

/**
 * Form step composable: renders native input controls for each FormField (SPEC-082).
 */
@Composable
fun FormStep(
    config: StepConfig,
    onNext: (Map<String, Any>?) -> Unit,
    /**
     * SPEC-070-A finalization B4 P1 — previously-entered responses
     * (one map keyed by field_id) used to restore form state on back nav.
     * Mirrors iOS FormStepView.savedValues (FormStepView.swift:9,119-123).
     */
    savedValues: Map<String, Any>? = null,
) {
    val fields = config.fields ?: emptyList()
    val values = remember { mutableStateMapOf<String, Any?>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    // Initialize defaults + savedValues. Order: savedValues (back-nav
    // restoration) wins over fieldDefaults wins over per-field default_value.
    LaunchedEffect(fields) {
        // SPEC-070-A finalization B4 P1 — restore from prior responses first.
        savedValues?.forEach { (fieldId, value) ->
            if (values[fieldId] == null) values[fieldId] = value
        }
        // SPEC-083: Apply fieldDefaults from StepConfigOverride next
        config.field_defaults?.forEach { (fieldId, value) ->
            if (values[fieldId] == null) {
                values[fieldId] = value
            }
        }
        // Then apply per-field default_value (doesn't override hook-injected defaults)
        fields.forEach { field ->
            if (values[field.id] == null) {
                field.config?.default_value?.let { values[field.id] = it }
            }
        }
    }

    val visibleFields = fields.filter { field ->
        val dep = field.depends_on ?: return@filter true
        val depValue = values[dep.field_id]
        when (dep.operator) {
            "not_empty", "is_set" -> depValue != null && depValue.toString().isNotEmpty()
            "empty" -> depValue == null || depValue.toString().isEmpty()
            "equals" -> depValue.toString() == dep.value.toString()
            "not_equals" -> depValue.toString() != dep.value.toString()
            "contains" -> depValue.toString().contains(dep.value.toString())
            "gt" -> (depValue as? Number)?.toDouble()?.let { it > (dep.value as? Number)?.toDouble() ?: 0.0 } ?: false
            "lt" -> (depValue as? Number)?.toDouble()?.let { it < (dep.value as? Number)?.toDouble() ?: 0.0 } ?: false
            else -> true
        }
    }

    val canSubmit = visibleFields.filter { it.required }.all { field ->
        val v = values[field.id]
        v != null && v.toString().isNotEmpty()
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // SPEC-401-A R46 (Lens A #4) — match iOS FormStepView.swift:61-62
                // `padding(.top, 16) + padding(.bottom, 24)`. Was vertical=16dp
                // both edges (16dp bottom on Android vs 24pt on iOS).
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // SPEC-070-A finalization B4 P1 — image_url header above the
            // title, capped at 120dp tall like iOS FormStepView.swift:22-32.
            // Uses Coil via NetworkImage so SVG / PNG / GIF all render.
            config.image_url?.takeIf { it.isNotBlank() }?.let { url ->
                ai.appdna.sdk.core.NetworkImage(
                    url = url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        // SPEC-401-A R46 (Lens A #3) — match iOS
                        // .clipShape(RoundedRectangle(cornerRadius: 12))
                        // (FormStepView.swift:29). Was sharp corners.
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    contentDescription = null,
                )
                // SPEC-401-A R53 (Lens A R53 #2, P2) — image→title 12→20dp
                // matching iOS FormStepView.swift:18-43 VStack(spacing: 20).
                Spacer(Modifier.height(20.dp))
            }

            // Header
            config.title?.let {
                Text(
                    text = it.interpolated(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            config.subtitle?.let {
                // SPEC-401-A R45 (Lens A #2 + #3) — match iOS FormStepView.swift:34-43
                // VStack(spacing: 20) → title→subtitle gap 20pt (was 4dp)
                // and .body font ≈ 17pt (was 15sp).
                Spacer(Modifier.height(20.dp))
                Text(
                    text = it.interpolated(),
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Fields
            visibleFields.forEach { field ->
                if (field.type != FormFieldType.TOGGLE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // SPEC-401-A R52 (Lens A R51 #18, P3) — 14→15sp
                        // matching iOS .subheadline.weight(.medium) at
                        // FormStepView.swift:208.
                        Text(
                            text = field.label.interpolated(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (field.required) {
                            Text(
                                text = " *",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // SPEC-401-A R46 (Lens A #2) — iOS label→control gap 6pt
                    // (FormStepView.swift:47 VStack(spacing: 6)). Was 4dp.
                    Spacer(Modifier.height(6.dp))
                }

                FormFieldControl(field, values, errors)

                errors[field.id]?.let { error ->
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // SPEC-401-A R46 (Lens A #1) — iOS field→field gap 20pt
                // (FormStepView.swift:20 VStack(spacing: 20)). Was 16dp.
                Spacer(Modifier.height(20.dp))
            }
        }

        // CTA
        // SPEC-401-A R17 — match iOS FormStepView.swift:77-115. iOS keeps the
        // button always-tappable and on tap with `canSubmit == false` shows a
        // 2.5s bottom toast naming the first missing required field. Android
        // previously used `enabled = canSubmit` which rendered a greyed-out
        // CTA the user could not click → no feedback on what was missing.
        // Now the button stays tappable; on tap with missing required fields,
        // populate `errors[fieldId]` for each missing field so the inline red
        // text under each input is the equivalent feedback channel.
        // SPEC-401-A R45 (Lens A #1) — honour element_style.background.color
        // + corner_radius. iOS FormStepView.swift:71-94 reads
        // `config.element_style?.background?.color ?? "#6366F1"` and
        // `corner_radius ?? 14`. Android was using Material default
        // (M3 primary purple) and hardcoded 14dp shape regardless of
        // brand authoring. Now reads element_style background + corner_radius.
        val ctaBgColor = config.element_style?.background?.color
            ?.let { StyleEngine.parseColor(it) }
            ?: StyleEngine.parseColor((ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"))
        val ctaCornerRadius = (config.element_style?.corner_radius ?: 14.0).dp
        Button(
            onClick = {
                errors.clear()
                // Validate
                visibleFields.forEach { field ->
                    if (field.required) {
                        val v = values[field.id]
                        if (v == null || v.toString().isEmpty()) {
                            errors[field.id] = "${field.label} is required"
                        }
                    }
                    field.validation?.pattern?.let { pattern ->
                        val v = values[field.id]?.toString() ?: ""
                        // SPEC-070-A I.5 — `containsMatchIn` for partial-match
                        // parity with iOS `NSRegularExpression.firstMatch`.
                        // Authors typically write patterns like `\d{6}` to test
                        // *contains* a 6-digit run, not anchored full-string.
                        if (v.isNotEmpty() && !Regex(pattern).containsMatchIn(v)) {
                            errors[field.id] = field.validation.pattern_message ?: "Invalid format"
                        }
                    }
                }
                if (errors.isEmpty()) {
                    // Round-22 — TimeField writes a display-only `${id}_time` companion into `values`;
                    // strip those before submit so they don't ship in selection_data/responses (iOS has
                    // no such companion key). Round-23 — key off ALL `fields` (not `visibleFields`): a
                    // TIME/DATETIME field filled then HIDDEN by a depends_on change keeps its companion in
                    // `values`, and scoping to visible fields would leak it. Mirrors iOS building its
                    // date-field type map from all `fields`.
                    val timeCompanions = fields
                        .filter { it.type == FormFieldType.TIME || it.type == FormFieldType.DATETIME }
                        .map { "${it.id}_time" }
                        .toSet()
                    val response = values
                        .filterKeys { it !in timeCompanions }
                        .mapValues { (_, v) -> v ?: "" }
                    @Suppress("UNCHECKED_CAST")
                    onNext(response as Map<String, Any>)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                // SPEC-401-A R45 (Lens A #4) — iOS CTA frame.height(54)
                // (was 52.dp).
                .height(54.dp),
            shape = RoundedCornerShape(ctaCornerRadius),
            // SPEC-401-A R60 (Lens C P2 #2) — dim only the background, keep
            // the label opaque white. Was `Modifier.graphicsLayer { alpha =
            // 0.4f }` on the whole Button which dropped both bg AND text to
            // 40% alpha — perceived contrast ~1.6:1 (fails WCAG AA). iOS
            // keeps text opaque white on accent@40% bg
            // (FormStepView.swift:87-93). Button stays `enabled` so onClick
            // still runs validation + surfaces inline errors per R17 design
            // (do NOT set `enabled = canSubmit` — see comment at line 194).
            // Compute containerColor manually instead.
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canSubmit) ctaBgColor else ctaBgColor.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                text = (config.cta_text ?: "Continue").interpolated(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormFieldControl(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    when (field.type) {
        FormFieldType.TEXT, FormFieldType.EMAIL, FormFieldType.PHONE -> {
            // SPEC-070-A I.5b — keyboardType selection now respects the
            // optional `field.config.keyboard_type` override ("url",
            // "number", "decimal", "phone", "email", "ascii", "default").
            // Falls back to the FormFieldType-derived default. iOS reads
            // `keyboard_type` from `OnboardingConfig.keyboard_type` to back
            // the same field-level override; Android does the same.
            val keyboardType = resolveKeyboardType(field)
            val maxLen = field.config?.max_length
            OutlinedTextField(
                value = values[field.id]?.toString() ?: "",
                onValueChange = { input ->
                    val truncated = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
                    values[field.id] = truncated
                    errors.remove(field.id)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                // SPEC-070-A I.5b — `imeAction = Done` so the soft keyboard
                // shows a "Done" key that dismisses the IME. Mirrors iOS
                // `submitLabel(.done)`.
                // SPEC-401-A B3 P2 — autocaps + autocorrect now respect the
                // field config and the field type. Email/phone disable both
                // (matching iOS implicit behavior); text fields default to
                // sentences capitalisation + autocorrect on; `autocorrect`
                // and `autocapitalize` config fields override.
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrect = resolveAutocorrect(field),
                    capitalization = resolveCapitalization(field),
                ),
                isError = errors.containsKey(field.id),
                singleLine = true,
                shape = textFieldShapeFor(field),
                colors = textFieldColorsFor(field),
            )
        }

        FormFieldType.TEXTAREA -> {
            val maxLen = field.config?.max_length
            val minLines = field.config?.multiline_min_lines ?: 3
            OutlinedTextField(
                value = values[field.id]?.toString() ?: "",
                onValueChange = { input ->
                    val truncated = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
                    values[field.id] = truncated
                    errors.remove(field.id)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // SPEC-401-A — cap height at 150dp to match iOS
                    // FormStepView.swift:274 so long input doesn't push the
                    // CTA off-screen on small phones. minLines still drives
                    // the initial visible row count.
                    .heightIn(min = (minLines * 24).dp, max = 150.dp),
                placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                isError = errors.containsKey(field.id),
                minLines = minLines,
                maxLines = 8,
                shape = textFieldShapeFor(field),
                colors = textFieldColorsFor(field),
            )
        }

        FormFieldType.PASSWORD -> PasswordField(field, values, errors)

        FormFieldType.URL -> UrlField(field, values, errors)

        FormFieldType.NUMBER -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = values[field.id]?.toString() ?: "",
                    onValueChange = {
                        values[field.id] = it.toDoubleOrNull() ?: it
                        errors.remove(field.id)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                    // SPEC-070-A I.5b — number keyboard + Done IME action.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    isError = errors.containsKey(field.id),
                    singleLine = true,
                    shape = textFieldShapeFor(field),
                    colors = textFieldColorsFor(field),
                )
                field.config?.unit?.let { unit ->
                    Spacer(Modifier.width(8.dp))
                    // SPEC-401-A R57 (Lens A R57 #7, P2) — 15sp matches iOS
                    // .font(.subheadline) at FormStepView.swift:326.
                    Text(text = unit, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }

        FormFieldType.DATE -> {
            DateField(field, values, errors)
        }

        FormFieldType.TIME -> {
            TimeField(field, values, errors)
        }

        FormFieldType.DATETIME -> {
            DateField(field, values, errors)
            Spacer(Modifier.height(8.dp))
            TimeField(field, values, errors)
        }

        FormFieldType.SELECT -> {
            SelectField(field, values, errors)
        }

        FormFieldType.SLIDER -> {
            // SPEC-419 pass-33 — clamp so Compose Slider valueRange isn't empty (its internal coerceIn throws on min>=max).
            val rawStep = field.config?.step?.toFloat() ?: 1f
            val step = if (rawStep > 0f) rawStep else 1f
            val min = minOf(field.config?.min_value?.toFloat() ?: 0f, field.config?.max_value?.toFloat() ?: 100f)
            val max = maxOf(field.config?.max_value?.toFloat() ?: 100f, min + step)
            val unit = field.config?.unit ?: ""
            val decimalPlaces = field.config?.decimal_places ?: 0
            val current = (values[field.id] as? Number)?.toFloat() ?: min
            // SPEC-401-A R59 (Lens C P2 #1) — discrete-step haptic tick on
            // each step crossing matching iOS native `Slider(step:)` which
            // auto-emits UISelectionFeedbackGenerator. Compose Slider has
            // no equivalent; gate on bucket-index change so continuous
            // sliders (step==0) stay silent.
            val sliderHapticView = androidx.compose.ui.platform.LocalView.current
            val sliderLastBucket = remember(field.id) { mutableStateOf(if (step > 0) ((current - min) / step).toInt() else 0) }

            Text(
                text = "${formatNumber(current, decimalPlaces)}${if (unit.isNotEmpty()) " $unit" else ""}",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Slider(
                value = current,
                onValueChange = { v ->
                    values[field.id] = v.toDouble()
                    if (step > 0 && max > min) {
                        val bucket = ((v - min) / step).toInt()
                        if (bucket != sliderLastBucket.value) {
                            sliderLastBucket.value = bucket
                            ai.appdna.sdk.core.HapticEngine.trigger(sliderHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                        }
                    }
                },
                valueRange = min..max,
                steps = if (step > 0 && max > min) (((max - min) / step).toInt() - 1).coerceAtLeast(0) else 0,  // SPEC-419 pass-34 — coerceAtLeast(0): Compose require(steps>=0) (mirror pass-22)
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatNumber(min, decimalPlaces), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                Text(formatNumber(max, decimalPlaces), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        }

        FormFieldType.RANGE_SLIDER -> RangeSliderField(field, values, errors)

        FormFieldType.RATING -> RatingField(field, values, errors)

        FormFieldType.IMAGE_PICKER -> ImagePickerField(field, values, errors)

        FormFieldType.COLOR -> ColorPickerField(field, values, errors)

        FormFieldType.MULTILINE_CHIPS -> MultilineChipsField(field, values, errors)

        FormFieldType.SIGNATURE -> SignatureField(field, values, errors)

        FormFieldType.TOGGLE -> {
            // SPEC-401-A — `field.style.background_color` (or input_style.fill_color
            // sub-key) maps to the Switch's `checkedTrackColor`. Mirrors iOS
            // `Toggle.tint`. Thumb-on stays the Material standard (white)
            // because that's the Material idiom — author-overriding it would
            // make the toggle look non-Android.
            val trackColorHex: String? = field.style?.background_color
                ?: (field.style?.input_style?.get("fill_color") as? String)
                ?: (field.style?.input_style?.get("background_color") as? String)
            val checkedTrackColor = trackColorHex?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
                ?: MaterialTheme.colorScheme.primary
            // SPEC-401-A R63 (Lens C P2) — wrap label+Switch in `toggleable`
            // so TalkBack treats the row as a single switch element matching
            // iOS Toggle("label", isOn:) at FormStepView.swift:445-459.
            // Switch becomes presentational (onCheckedChange = null); the
            // Row owns the click + a11y semantics. R60 fixed this in the
            // v2 paths (ContentBlockRenderer.kt:5511 + 1762) but the legacy
            // FormStep TOGGLE path was missed.
            val toggleChecked = values[field.id] as? Boolean ?: false
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = toggleChecked,
                        role = androidx.compose.ui.semantics.Role.Switch,
                        onValueChange = { values[field.id] = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(field.label.interpolated(), fontSize = 16.sp)
                Switch(
                    checked = toggleChecked,
                    // SPEC-401-A R63 (Lens C P2) — null handler so the Row's
                    // toggleable owns the click + a11y semantics.
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = checkedTrackColor,
                    ),
                )
            }
        }

        FormFieldType.STEPPER -> {
            val min = field.config?.min_value ?: 0.0
            val max = field.config?.max_value ?: 100.0
            val step = field.config?.step ?: 1.0
            val decimalPlaces = field.config?.decimal_places ?: 0
            val current = (values[field.id] as? Number)?.toDouble() ?: min
            // SPEC-401-A R49 (Lens C #3, P2) — replace heavy LongPress haptic
            // with HapticEngine LIGHT (HapticFeedbackConstants.CLOCK_TICK).
            // iOS uses UIImpactFeedbackGenerator(style: .light) for stepper
            // ± buttons (FormFieldRendererExtras.swift:113); LongPress
            // produced a much heavier buzz than the gentle iOS tap.
            val view = androidx.compose.ui.platform.LocalView.current

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (current - step >= min) {
                            values[field.id] = current - step
                            ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                        }
                    },
                    enabled = current - step >= min,
                    // SPEC-401-A R58 (Lens C P2) — TalkBack reads "Decrease"
                    // matching iOS native UIStepper auto-announcement +
                    // sibling content-block path R38 fix at
                    // ContentBlockRenderer.kt:5371. Was generic "Button".
                    modifier = Modifier.semantics { contentDescription = "Decrease" },
                ) {
                    Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                // SPEC-401-A B3 P2 — animate value transition on
                // increment/decrement. iOS `Stepper` springs implicitly;
                // Compose AnimatedContent with the default fade transition
                // gives the same visual cue ("the number changed") without
                // depending on `togetherWith` (only available in
                // compose-animation 1.7+; we're on BoM 2024.02.02).
                androidx.compose.animation.AnimatedContent(
                    targetState = current,
                    label = "stepper_value",
                ) { displayed ->
                    // SPEC-401-A R70 (Lens A P2) — split value + unit into TWO
                    // Text nodes: number at 17sp SemiBold (iOS .font(.headline))
                    // and unit at 15sp .secondary tone (iOS .font(.subheadline)
                    // .foregroundColor(.secondary)) at FormFieldRendererExtras
                    // .swift:474-481. Was one combined Text — unit rendered at
                    // full primary weight, looking heavier than iOS.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatNumber(displayed, decimalPlaces),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 48.dp)
                        )
                        field.config?.unit?.let { unit ->
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = unit,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = {
                        if (current + step <= max) {
                            values[field.id] = current + step
                            // SPEC-401-A R49 (Lens C #3, P2) — LIGHT haptic.
                            ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                        }
                    },
                    enabled = current + step <= max,
                    // SPEC-401-A R58 (Lens C P2) — TalkBack reads "Increase"
                    // matching iOS UIStepper + ContentBlockRenderer R38 fix.
                    modifier = Modifier.semantics { contentDescription = "Increase" },
                ) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        FormFieldType.LOCATION -> {
            // SPEC-070-A I.18 — full autocomplete location input. Reuses the
            // dedicated `LocationFieldComposable` so the legacy form-step
            // path matches block-based rendering fidelity. Honours
            // `location_type`, `location_bias_country`, `location_language`,
            // `location_min_chars`, `location_placeholder` from
            // `OnboardingConfig.kt:204-208`.
            LocationFieldComposable(
                field = field,
                values = values,
                errors = errors,
            )
        }
        FormFieldType.SEGMENTED -> {
            // SPEC-401-A B2 P1 — replaced custom `OutlinedButton` loop with
            // Material3 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`.
            // The Material3 component handles per-position shape, selection
            // animation, RTL, and accessibility automatically — what we
            // hand-rolled before. Typography uses `labelMedium` (Material3
            // standard) instead of the previous hardcoded 13.sp.
            val options = field.options ?: emptyList()
            val selected = values[field.id]?.toString() ?: options.firstOrNull()?.id ?: ""
            if (options.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = selected == option.id,
                            onClick = { values[field.id] = option.id },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            label = {
                                Text(
                                    text = option.label.interpolated(),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val context = LocalContext.current
    val dateStr = values[field.id]?.toString() ?: ""

    // SPEC-401-A — display string honours config.date_format if author
    // supplied one (e.g. "MMM d, yyyy"). Otherwise locale-aware short
    // form (`SimpleDateFormat.getDateInstance(SHORT, Locale.getDefault())`)
    // so dates feel native in any region. ISO submission stays Locale.US.
    val displayFormatter = remember(field.config?.date_format) {
        try {
            field.config?.date_format?.takeIf { it.isNotBlank() }?.let { SimpleDateFormat(it, Locale.getDefault()) }
                ?: SimpleDateFormat.getDateInstance(SimpleDateFormat.SHORT, Locale.getDefault()) as SimpleDateFormat
        } catch (_: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }
    }
    val displayText: String = remember(dateStr) {
        if (dateStr.isEmpty()) "" else try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
            if (parsed != null) displayFormatter.format(parsed) else dateStr
        } catch (_: Exception) { dateStr }
    }

    OutlinedButton(
        onClick = {
            val cal = Calendar.getInstance()
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    // SPEC-070-A I.6 — ISO8601 (`yyyy-MM-dd`) submission
                    // matches iOS `DateFormatter.iso8601` round-trip exactly.
                    // Locale.US prevents Arabic-numeral substitution
                    // (Locale.getDefault() in fa-IR / ar would format
                    // `۱۴۰۲-۰۱-۰۵` and break server geocoding).
                    val formatted = String.format(
                        Locale.US,
                        "%04d-%02d-%02d",
                        year, month + 1, day,
                    )
                    values[field.id] = formatted
                    errors.remove(field.id)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            )
            // SPEC-401-A — enforce min_date/max_date config; previously the
            // schema accepted them but the picker ignored them. ISO8601
            // `yyyy-MM-dd` parsed via SimpleDateFormat(Locale.US).
            try {
                val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                field.config?.min_date?.let { iso ->
                    isoFmt.parse(iso)?.let { dialog.datePicker.minDate = it.time }
                }
                field.config?.max_date?.let { iso ->
                    isoFmt.parse(iso)?.let { dialog.datePicker.maxDate = it.time }
                }
            } catch (_: Exception) { /* tolerate parse failure */ }
            dialog.show()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = displayText.ifEmpty { field.placeholder ?: "Select date" },
            color = if (dateStr.isEmpty())
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TimeField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val context = LocalContext.current
    val timeKey = "${field.id}_time"
    val timeStr = values[timeKey]?.toString() ?: ""

    OutlinedButton(
        onClick = {
            // SPEC-401-A R81 (Lens A P1) — seed picker with previously-saved
            // time matching iOS FormStepView.swift:365-377 binding which
            // returns `values[field.id] as? Date ?? Date()`. Was always
            // seeding with current wall-clock — re-opening after picking
            // 09:30 displayed "now" instead of the saved choice. Misleading
            // UX during edit/cancel flows.
            val cal = Calendar.getInstance()
            val savedTime = timeStr.takeIf { it.matches(Regex("\\d{2}:\\d{2}")) }
            if (savedTime != null) {
                cal.set(Calendar.HOUR_OF_DAY, savedTime.substringBefore(':').toInt())
                cal.set(Calendar.MINUTE, savedTime.substringAfter(':').toInt())
            }
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    // SPEC-070-A I.6/I.10 — `Locale.US` keeps digits ASCII-numeric
                    // so the time round-trips with iOS server geocoding regardless
                    // of device locale (Arabic / Persian / Bengali otherwise emit
                    // their native numerals here).
                    val formatted = String.format(Locale.US, "%02d:%02d", hour, minute)
                    values[timeKey] = formatted
                    // Round-22 — deterministic LOCAL-component serialization matching iOS FormStepView
                    // (which now formats date/time by field type with no UTC conversion):
                    //   datetime → "yyyy-MM-dd'T'HH:mm:ss"  (append :00 seconds to the date+time)
                    //   time     → "HH:mm"                  (bare, no anchor date)
                    // The old code emitted "yyyy-MM-ddTHH:mm" (no seconds) for datetime and a
                    // 1970-01-01 UTC anchor for time-only — neither matched iOS.
                    val dateStr = values[field.id]?.toString() ?: ""
                    if (field.type == FormFieldType.DATETIME) {
                        // The DateField already wrote "yyyy-MM-dd" into the same key. If the date hasn't
                        // been picked yet, store just the time; it's combined once the date is chosen.
                        val datePart = dateStr.substringBefore('T').takeIf { it.isNotEmpty() }
                        values[field.id] = if (datePart != null) "${datePart}T$formatted:00" else formatted
                    } else {
                        values[field.id] = formatted
                    }
                    errors.remove(field.id)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                // SPEC-401-A R61 (Lens C P2) — honour user's locale 12/24
                // preference matching iOS DatePicker(.hourAndMinute) which
                // auto-respects system setting (FormStepView.swift:370-376).
                // Was hardcoded `true` — en-US/AU/CA/PH (12-hour-default
                // majority) always saw 24h on legacy onboarding flows. v2
                // Material3 path at ContentBlockRenderer.kt:5036 already
                // correct via DateFormat.is24HourFormat in
                // rememberTimePickerState default.
                android.text.format.DateFormat.is24HourFormat(context)
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = timeStr.ifEmpty { field.placeholder ?: "Select time" },
            color = if (timeStr.isEmpty())
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * SPEC-401-A — derive Material3 `OutlinedTextFieldColors` from the
 * per-field `style` envelope. Authors set `border_color`,
 * `focus_border_color`, `background_color` directly, or stuff a
 * sub-color into the free-form `error_style.color` map for the error
 * tint. Falls back to Material3 defaults so no styling means stock
 * appearance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldColorsFor(field: FormField): TextFieldColors {
    val style = field.style ?: return OutlinedTextFieldDefaults.colors()
    val parse: (String?) -> androidx.compose.ui.graphics.Color? = { hex ->
        hex?.takeIf { it.isNotBlank() }?.let { ai.appdna.sdk.core.StyleEngine.parseColor(it) }
    }
    val border = parse(style.border_color)
    val focusedBorder = parse(style.focus_border_color)
    val container = parse(style.background_color)
    val errorHex = (style.error_style?.get("color") as? String)
        ?: (style.error_style?.get("border_color") as? String)
    val errorBorder = parse(errorHex)

    // QA-R16 (broadened) — same Compose Material3 footgun as the
    // password content-block: unset `focused_border_color` falls through to
    // `colorScheme.primary`, which is M3 default purple on the dark scheme.
    // Override with white-on-translucent so authored fields without an
    // explicit colour render legibly over the step's dark gradient. Hosts
    // running a light Compose theme also benefit — `colorScheme.primary` on
    // the light scheme is M3's deep purple (`#6750A4`), still divergent
    // from the iOS-canonical indigo / system tint.
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = focusedBorder ?: androidx.compose.ui.graphics.Color.White,
        unfocusedBorderColor = border ?: androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
        errorBorderColor = errorBorder ?: MaterialTheme.colorScheme.error,
        focusedContainerColor = container ?: androidx.compose.ui.graphics.Color.Transparent,
        unfocusedContainerColor = container ?: androidx.compose.ui.graphics.Color.Transparent,
        disabledContainerColor = container ?: androidx.compose.ui.graphics.Color.Transparent,
        errorContainerColor = container ?: androidx.compose.ui.graphics.Color.Transparent,
        cursorColor = focusedBorder ?: androidx.compose.ui.graphics.Color.White,
        focusedLabelColor = focusedBorder ?: androidx.compose.ui.graphics.Color.White,
        unfocusedLabelColor = border ?: androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
        focusedTextColor = androidx.compose.ui.graphics.Color.White,
        unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
    )
}

/**
 * SPEC-401-A — derive a [Shape] from the per-field `style.corner_radius`
 * with a sensible default (Material3 OutlinedTextField default = 4.dp,
 * iOS `.roundedBorder` ≈ 5pt; we use 8.dp for visual harmony with
 * other Material3 surfaces).
 */
@Composable
private fun textFieldShapeFor(field: FormField): androidx.compose.ui.graphics.Shape {
    val radius = field.style?.corner_radius?.toFloat() ?: return OutlinedTextFieldDefaults.shape
    return RoundedCornerShape(radius.dp)
}

/**
 * SPEC-401-A B3 P2 — autocorrect default per field type. Email/phone/url/
 * password switch autocorrect off (matches iOS implicit behaviour and
 * prevents the IME from "correcting" structured text). Other text types
 * default to autocorrect on. The `autocorrect` config field overrides.
 */
private fun resolveAutocorrect(field: FormField): Boolean {
    field.config?.autocorrect?.let { return it }
    return when (field.type) {
        FormFieldType.EMAIL, FormFieldType.PHONE, FormFieldType.URL,
        FormFieldType.PASSWORD -> false
        else -> true
    }
}

/**
 * SPEC-401-A B3 P2 — KeyboardCapitalization default per field type +
 * config-overridable. iOS implicit defaults are: email/phone/url/password →
 * none; text → sentences. The `autocapitalize` config field accepts the
 * same vocabulary as the schema enum (`none`/`words`/`sentences`/`characters`).
 */
private fun resolveCapitalization(field: FormField): KeyboardCapitalization {
    field.config?.autocapitalize?.lowercase()?.let { override ->
        when (override) {
            "none" -> return KeyboardCapitalization.None
            "words" -> return KeyboardCapitalization.Words
            "sentences" -> return KeyboardCapitalization.Sentences
            "characters" -> return KeyboardCapitalization.Characters
            else -> { /* fall through to type-based default */ }
        }
    }
    return when (field.type) {
        FormFieldType.EMAIL, FormFieldType.PHONE, FormFieldType.URL,
        FormFieldType.PASSWORD -> KeyboardCapitalization.None
        else -> KeyboardCapitalization.Sentences
    }
}

/**
 * SPEC-070-A I.5b — derive a [KeyboardType] for a [FormField]. Honours the
 * optional `keyboard_type` config override ("url", "number", "decimal",
 * "phone", "email", "ascii", "default") and falls back to the field type.
 */
private fun resolveKeyboardType(field: FormField): KeyboardType {
    val override = field.config?.keyboard_type?.lowercase()
    if (override != null) {
        when (override) {
            "url", "uri" -> return KeyboardType.Uri
            "number", "numeric" -> return KeyboardType.Number
            "decimal" -> return KeyboardType.Decimal
            "phone", "tel" -> return KeyboardType.Phone
            "email" -> return KeyboardType.Email
            "ascii" -> return KeyboardType.Ascii
            "password" -> return KeyboardType.Password
            "number_password" -> return KeyboardType.NumberPassword
            "default", "text" -> return KeyboardType.Text
        }
    }
    return when (field.type) {
        FormFieldType.EMAIL -> KeyboardType.Email
        FormFieldType.PHONE -> KeyboardType.Phone
        FormFieldType.NUMBER -> KeyboardType.Number
        else -> KeyboardType.Text
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>
) {
    val options = field.options ?: emptyList()
    val multiSelect = field.config?.multi_select == true
    val maxSelections = field.config?.max_selections

    // #596 — a form Select renders through the SAME engine the `input_select` CONTENT block uses
    // whenever the author picked a layout other than the dropdown.
    //
    // The console offers six display styles on a form Select and this composable honoured NONE of
    // them: it always produced an ExposedDropdownMenu, while the console preview drew the tiles it
    // promised. iOS had the identical gap (a `.menu` Picker), so the control was dead on both
    // platforms.
    //
    // Delegating rather than reimplementing is the point. Six layouts, category chips, option sets
    // and twenty styling keys already exist in `FormInputSelectBlock`; a second copy here would
    // diverge from it by the next release.
    //
    // `dropdown` (the default, and every flow that never touched the setting) keeps the native
    // menu, so nothing that renders correctly today changes. iOS parity: FormStepView.selectField.
    val displayStyle = field.config_raw?.get("display_style") as? String ?: "dropdown"
    if (displayStyle != "dropdown") {
        val block = selectBlockFrom(field)
        if (block != null) {
            // `values` is `MutableMap<String, Any?>`; the block engine writes non-null values only,
            // so the bridge below copies results back rather than casting the map.
            val bridge = remember(field.id) { mutableStateMapOf<String, Any>() }
            LaunchedEffect(values[field.id]) {
                values[field.id]?.let { bridge[field.id] = it }
            }
            FormInputSelectBlockPublic(block, bridge)
            LaunchedEffect(bridge[field.id]) {
                bridge[field.id]?.let {
                    values[field.id] = it
                    errors.remove(field.id)
                }
            }
            return
        }
    }

    if (multiSelect) {
        @Suppress("UNCHECKED_CAST")
        val selected: List<String> = (values[field.id] as? List<String>)
            ?: ((values[field.id] as? String)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList())
        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = if (selected.isEmpty()) ""
            else selected.mapNotNull { id -> options.find { it.id == id }?.label?.interpolated() }.joinToString(", ")

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                isError = errors.containsKey(field.id),
                shape = textFieldShapeFor(field),
                colors = textFieldColorsFor(field),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    val checked = option.id in selected
                    DropdownMenuItem(
                        text = { Text(option.label.interpolated()) },
                        onClick = {
                            val updated = selected.toMutableList()
                            if (checked) {
                                updated.remove(option.id)
                            } else {
                                if (maxSelections == null || updated.size < maxSelections) {
                                    updated.add(option.id)
                                }
                            }
                            values[field.id] = updated.toList()
                            errors.remove(field.id)
                        },
                        leadingIcon = {
                            if (checked) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                )
                            } else {
                                Spacer(Modifier.width(24.dp))
                            }
                        },
                    )
                }
            }
        }
        // Done button when max reached or just to confirm
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (maxSelections != null) "${selected.size} / $maxSelections selected" else "${selected.size} selected",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selected = values[field.id]?.toString() ?: ""
    val selectedLabel = options.find { it.id == selected }?.label?.interpolated() ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = errors.containsKey(field.id)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label.interpolated()) },
                    // SPEC-401-A B2 P1 — render `option.icon` as a leading
                    // image when set. Schema field has been there since
                    // SPEC-082 but Android dropdown was text-only. Treats
                    // values starting with "http" as a URL (Coil) and
                    // everything else as text — emoji works too.
                    leadingIcon = option.icon?.let { iconStr ->
                        {
                            if (iconStr.startsWith("http", ignoreCase = true)) {
                                ai.appdna.sdk.core.NetworkImage(
                                    url = iconStr,
                                    modifier = Modifier.size(20.dp),
                                    contentDescription = null,
                                )
                            } else {
                                Text(text = iconStr, fontSize = 16.sp)
                            }
                        }
                    },
                    onClick = {
                        values[field.id] = option.id
                        errors.remove(field.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
