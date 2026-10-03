package ai.appdna.sdk.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.interpolated
import kotlin.math.roundToInt

/**
 * Renderers for the form-field types added in the schema but
 * previously unimplemented in the Android SDK: PASSWORD, URL, RATING,
 * RANGE_SLIDER, IMAGE_PICKER, COLOR, MULTILINE_CHIPS, SIGNATURE.
 *
 * Each renderer is intentionally a thin Compose function — the heavy lifting
 * (storage, lifecycle, validation) lives in `FormStepComposable.kt`. These
 * are called from the central `when (field.type)` dispatch.
 */

// ─── Password ────────────────────────────────────────────────────────

@Composable
internal fun PasswordField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val text = values[field.id]?.toString() ?: ""
    val maxLen = field.config?.max_length
    val colors = passwordTextFieldColors(field)
    val shape = passwordTextFieldShape(field)

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val truncated = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
            values[field.id] = truncated
            errors.remove(field.id)
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        isError = errors.containsKey(field.id),
        shape = shape,
        colors = colors,
    )
}

// ─── URL ─────────────────────────────────────────────────────────────

@Composable
internal fun UrlField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val text = values[field.id]?.toString() ?: ""
    val maxLen = field.config?.max_length
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val truncated = if (maxLen != null && input.length > maxLen) input.take(maxLen) else input
            values[field.id] = truncated
            errors.remove(field.id)
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = field.placeholder?.let { { Text(it.interpolated()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        isError = errors.containsKey(field.id),
        shape = passwordTextFieldShape(field),
        colors = passwordTextFieldColors(field),
    )
}

/**
 * Local copy of `textFieldShapeFor` / `textFieldColorsFor`
 * from `FormStepComposable.kt`. Inlined here so this file stays
 * self-contained and the two private helpers in FormStepComposable.kt
 * don't have to be promoted to module-internal. Both renderers honour
 * the same `field.style` envelope.
 */
@Composable
private fun passwordTextFieldShape(field: FormField): androidx.compose.ui.graphics.Shape {
    val radius = field.style?.corner_radius?.toFloat() ?: return OutlinedTextFieldDefaults.shape
    return RoundedCornerShape(radius.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun passwordTextFieldColors(field: FormField): TextFieldColors {
    val style = field.style ?: return OutlinedTextFieldDefaults.colors()
    val parse: (String?) -> Color? = { hex ->
        hex?.takeIf { it.isNotBlank() }?.let { StyleEngine.parseColor(it) }
    }
    val border = parse(style.border_color)
    val focusedBorder = parse(style.focus_border_color)
    val container = parse(style.background_color)
    val errorHex = (style.error_style?.get("color") as? String)
        ?: (style.error_style?.get("border_color") as? String)
    val errorBorder = parse(errorHex)
    // QA-R16 (broadened) — mirror the FormStepComposable
    // `textFieldColorsFor` change (line 803): default to white-on-
    // translucent instead of M3 `colorScheme.primary` (purple). Same
    // Compose Material3 footgun — Password and URL fields rendered with
    // a purple focused border on any host using the default M3 scheme.
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = focusedBorder ?: Color.White,
        unfocusedBorderColor = border ?: Color.White.copy(alpha = 0.3f),
        errorBorderColor = errorBorder ?: MaterialTheme.colorScheme.error,
        focusedContainerColor = container ?: Color.Transparent,
        unfocusedContainerColor = container ?: Color.Transparent,
        disabledContainerColor = container ?: Color.Transparent,
        errorContainerColor = container ?: Color.Transparent,
        cursorColor = focusedBorder ?: Color.White,
        focusedLabelColor = focusedBorder ?: Color.White,
        unfocusedLabelColor = border ?: Color.White.copy(alpha = 0.7f),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
    )
}

// ─── Rating ──────────────────────────────────────────────────────────

@Composable
internal fun RatingField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val maxStars = (field.config?.max_stars ?: 5).coerceIn(3, 10)
    val allowHalf = field.config?.allow_half == true
    val starSize = (field.config?.star_size ?: 32).dp
    val filledColor = field.config?.filled_color?.let { StyleEngine.parseColor(it) }
        ?: MaterialTheme.colorScheme.primary
    val emptyColor = field.config?.empty_color?.let { StyleEngine.parseColor(it) }
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    val current = (values[field.id] as? Number)?.toDouble() ?: 0.0
    // Replace heavy LongPress haptic with
    // HapticEngine LIGHT (CLOCK_TICK), matching iOS .light impact at
    // FormFieldRendererExtras.swift:113.
    val view = androidx.compose.ui.platform.LocalView.current

    // Combined a11y group: per-star
    // contentDescription is rolled up into a single "Rating" semantic with
    // stateDescription mirroring iOS combined-element pattern at
    // FormFieldRendererExtras.swift:116. Per-star labels are dropped from
    // the merged group and TalkBack reads "Rating, $current of $maxStars
    // stars" once instead of star-by-star.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Rating"
            stateDescription = "$current of $maxStars stars"
            // Polite live region so TalkBack
            // announces stateDescription change immediately when the user
            // taps a star, without requiring re-focus. iOS SwiftUI
            // `.accessibilityValue("...stars")` auto-announces on state
            // change at ContentBlockStandaloneViews.swift:54-55.
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        },
    ) {
        for (i in 1..maxStars) {
            val starState = when {
                current >= i -> 1.0
                allowHalf && current >= i - 0.5 -> 0.5
                else -> 0.0
            }
            Box(
                modifier = Modifier
                    .size(starSize)
                    .pointerInput(allowHalf, i) {
                        detectTapGestures(
                            onTap = { offset ->
                                // Half-star detection: tap on left half = i - 0.5, right half = i.
                                val newRating = if (allowHalf && offset.x < size.width / 2f) {
                                    (i - 0.5).coerceAtLeast(0.5)
                                } else {
                                    i.toDouble()
                                }
                                val rounded = if (allowHalf) newRating else newRating.toInt().toDouble()
                                values[field.id] = rounded
                                errors.remove(field.id)
                                ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (starState) {
                    1.0 -> Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = filledColor,
                        modifier = Modifier.size(starSize),
                    )
                    0.5 -> Icon(
                        imageVector = Icons.Filled.StarHalf,
                        contentDescription = null,
                        tint = filledColor,
                        modifier = Modifier.size(starSize),
                    )
                    else -> Icon(
                        imageVector = Icons.Outlined.StarOutline,
                        contentDescription = null,
                        tint = emptyColor,
                        modifier = Modifier.size(starSize),
                    )
                }
            }
        }
    }
}

// ─── Range Slider ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeSliderField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    // Clamp so Compose RangeSlider valueRange isn't empty (its internal coerceIn throws on min>=max).
    val rawStep = field.config?.step?.toFloat() ?: 1f
    val step = if (rawStep > 0f) rawStep else 1f
    val min = minOf(field.config?.min_value?.toFloat() ?: 0f, field.config?.max_value?.toFloat() ?: 100f)
    val max = maxOf(field.config?.max_value?.toFloat() ?: 100f, min + step)
    val unit = field.config?.unit ?: ""
    val decimalPlaces = field.config?.decimal_places ?: 0

    val rawLow = (values["${field.id}_low"] as? Number)?.toFloat()
        ?: ((values[field.id] as? Map<*, *>)?.get("low") as? Number)?.toFloat()
        ?: min
    val rawHigh = (values["${field.id}_high"] as? Number)?.toFloat()
        ?: ((values[field.id] as? Map<*, *>)?.get("high") as? Number)?.toFloat()
        ?: max
    // Coerce both thumbs into [min,max] and order them so the RangeSlider `value` is never an empty range.
    val loC = rawLow.coerceIn(min, max)
    val hiC = rawHigh.coerceIn(min, max)
    val range = minOf(loC, hiC)..maxOf(loC, hiC)

    val fmtLow = formatNumber(range.start, decimalPlaces)
    val fmtHigh = formatNumber(range.endInclusive, decimalPlaces)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$fmtLow${if (unit.isNotEmpty()) " $unit" else ""}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Text(
                "$fmtHigh${if (unit.isNotEmpty()) " $unit" else ""}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        RangeSlider(
            value = range,
            onValueChange = { newRange ->
                values["${field.id}_low"] = newRange.start.toDouble()
                values["${field.id}_high"] = newRange.endInclusive.toDouble()
                values[field.id] = mapOf(
                    "low" to newRange.start.toDouble(),
                    "high" to newRange.endInclusive.toDouble(),
                )
                errors.remove(field.id)
            },
            valueRange = min..max,
            steps = if (step > 0 && max > min) (((max - min) / step).toInt() - 1).coerceAtLeast(0) else 0,  // coerceAtLeast(0): Compose require(steps>=0) (mirror pass-22)
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                field.config?.min_label?.interpolated() ?: formatNumber(min, decimalPlaces),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            Text(
                field.config?.max_label?.interpolated() ?: formatNumber(max, decimalPlaces),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Image Picker ────────────────────────────────────────────────────

@Composable
internal fun ImagePickerField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val context = LocalContext.current
    val maxSizeMb = field.config?.max_size_mb ?: 10.0
    val aspectRatio = field.config?.aspect_ratio ?: "free"
    val placeholder = field.config?.placeholder_text?.interpolated() ?: "Tap to add a photo"

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var showCrop by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Size guard
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val sizeMb = afd.length.toDouble() / (1024.0 * 1024.0)
                    if (sizeMb > maxSizeMb) {
                        errors[field.id] = "Image too large (${"%.1f".format(java.util.Locale.US, sizeMb)} MB max ${maxSizeMb} MB)"
                        return@rememberLauncherForActivityResult
                    }
                }
            } catch (_: Exception) { /* tolerate fd failures */ }

            pickedUri = uri
            showCrop = aspectRatio != "free" && aspectRatio.isNotEmpty()
            if (!showCrop) {
                values[field.id] = uri.toString()
                errors.remove(field.id)
            }
        }
    }

    Column {
        OutlinedButton(
            onClick = {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (pickedUri != null) "Change photo" else placeholder,
                fontSize = 14.sp,
            )
        }
        pickedUri?.let { uri ->
            Spacer(Modifier.height(8.dp))
            ai.appdna.sdk.core.NetworkImage(
                url = uri.toString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentDescription = null,
            )
        }
    }

    if (showCrop && pickedUri != null) {
        ImageCropDialog(
            uri = pickedUri!!,
            aspectRatio = aspectRatio,
            onCancel = { showCrop = false; pickedUri = null },
            onConfirm = { croppedUri ->
                values[field.id] = croppedUri
                errors.remove(field.id)
                showCrop = false
            },
        )
    }
}

@Composable
private fun ImageCropDialog(
    uri: Uri,
    aspectRatio: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    val ratio = parseAspectRatio(aspectRatio) ?: 1f

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Crop image (${aspectRatio})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
                // Show the image in a frame with the desired aspect ratio.
                // True pinch-zoom-pan crop is left as a follow-up; this v1 honours
                // the chosen aspect ratio via centerCrop while letting the user
                // confirm or re-pick. Material Photo Picker on Android 14+
                // already exposes its own crop UI, so this dialog primarily
                // provides a confirmation gate for older platforms.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(8.dp))
                        // Theme-aware backdrop
                        // (was hardcoded Color.Black). Harsh against
                        // transparent PNGs in dark mode; iOS lets the
                        // system surface show through.
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    ai.appdna.sdk.core.NetworkImage(
                        url = uri.toString(),
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        // Crop pipeline: load, scale, center-crop to ratio,
                        // and save to cache. The resulting URI is what we
                        // return through the form value.
                        val croppedUri = cropToAspectRatio(context, uri, ratio)
                        onConfirm(croppedUri ?: uri.toString())
                    }) { Text("Use") }
                }
            }
        }
    }
}

private fun parseAspectRatio(spec: String): Float? {
    val parts = spec.split(":", "/", "x")
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull() ?: return null
    val h = parts[1].toFloatOrNull() ?: return null
    if (h == 0f) return null
    return w / h
}

private fun cropToAspectRatio(
    context: android.content.Context,
    uri: Uri,
    ratio: Float,
): String? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val source = input.use { BitmapFactory.decodeStream(it) } ?: return null
        val sourceRatio = source.width.toFloat() / source.height
        val (cropW, cropH) = if (sourceRatio > ratio) {
            // Source is wider than target — crop horizontally.
            val w = (source.height * ratio).toInt()
            w to source.height
        } else {
            // Source is taller — crop vertically.
            val h = (source.width / ratio).toInt()
            source.width to h
        }
        val left = ((source.width - cropW) / 2).coerceAtLeast(0)
        val top = ((source.height - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(source, left, top, cropW, cropH)
        val cacheFile = java.io.File(context.cacheDir, "appdna-crop-${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(cacheFile).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        Uri.fromFile(cacheFile).toString()
    } catch (_: Exception) {
        null
    }
}

// ─── Color Picker ────────────────────────────────────────────────────

@Composable
internal fun ColorPickerField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val current = values[field.id]?.toString()
        ?: field.config?.default_color
        ?: (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1")
    val showOpacity = field.config?.show_opacity == true
    val presets = field.config?.preset_colors ?: defaultPresetColors()
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(StyleEngine.parseColor(current))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(current, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
    }

    if (showDialog) {
        ColorPickerDialog(
            initial = current,
            showOpacity = showOpacity,
            presets = presets,
            onDismiss = { showDialog = false },
            onConfirm = { hex ->
                values[field.id] = hex
                errors.remove(field.id)
                showDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    initial: String,
    showOpacity: Boolean,
    presets: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var hex by remember { mutableStateOf(initial.removePrefix("#")) }
    val cleanedHex = hex.trim().uppercase()
    val parsed = StyleEngine.parseColor("#$cleanedHex")
    val isValid = cleanedHex.length in setOf(6, 8) && cleanedHex.all { it in "0123456789ABCDEF" }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Pick a color", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))

                // Live preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(parsed)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(16.dp))

                // Hex input
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        hex = input.removePrefix("#").take(if (showOpacity) 8 else 6).filter {
                            it.isDigit() || it.uppercaseChar() in 'A'..'F' || it.lowercaseChar() in 'a'..'f'
                        }
                    },
                    label = { Text("Hex (${if (showOpacity) "RRGGBBAA" else "RRGGBB"})") },
                    leadingIcon = { Text("#", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = !isValid,
                )

                Spacer(Modifier.height(16.dp))
                Text("Presets", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    presets.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(StyleEngine.parseColor(preset))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                .clickable { hex = preset.removePrefix("#") },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm("#$cleanedHex") },
                        enabled = isValid,
                    ) { Text("Use") }
                }
            }
        }
    }
}

private fun defaultPresetColors(): List<String> = listOf(
    "#000000", "#FFFFFF", "#EF4444", "#F97316", "#F59E0B",
    "#10B981", "#06B6D4", "#3B82F6", (ai.appdna.sdk.AppDNA.brandAccentHex ?: "#6366F1"), "#8B5CF6",
    "#EC4899", "#6B7280",
)

// ─── Multiline Chips ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MultilineChipsField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val maxChips = field.config?.max_chips ?: 50
    val allowCustom = field.config?.allow_custom != false // default true
    val suggestions = field.config?.suggestions ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val selected = (values[field.id] as? List<String>)?.toMutableList()
        ?: mutableListOf<String>().also { values[field.id] = it }
    var input by remember { mutableStateOf("") }

    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            selected.forEach { chip ->
                InputChip(
                    selected = true,
                    onClick = {
                        val updated = selected.toMutableList().apply { remove(chip) }
                        values[field.id] = updated
                    },
                    label = { Text(chip) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove $chip",
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
        if (allowCustom && selected.size < maxChips) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(field.placeholder?.interpolated() ?: "Add tag") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val trimmed = input.trim()
                        if (trimmed.isNotEmpty() && selected.size < maxChips) {
                            val updated = (selected + trimmed).distinct().toMutableList()
                            values[field.id] = updated
                            errors.remove(field.id)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && selected.size < maxChips,
                ) { Icon(Icons.Filled.Add, contentDescription = "Add chip") }
            }
        }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Suggestions",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.filter { it !in selected }.forEach { suggestion ->
                    AssistChip(
                        onClick = {
                            if (selected.size < maxChips) {
                                val updated = (selected + suggestion).toMutableList()
                                values[field.id] = updated
                                errors.remove(field.id)
                            }
                        },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
    }
}

// ─── Signature ───────────────────────────────────────────────────────

@Composable
internal fun SignatureField(
    field: FormField,
    values: MutableMap<String, Any?>,
    errors: MutableMap<String, String>,
) {
    val context = LocalContext.current
    val strokeColor = field.config?.stroke_color?.let { StyleEngine.parseColor(it) } ?: Color.Black
    val strokeWidthDp = (field.config?.stroke_width ?: 2.5).toFloat().dp
    val clearText = field.config?.clear_button_text ?: "Clear"

    // List-of-strokes; each stroke is a list of x/y points captured during a drag.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            current.clear()
                            current.add(offset)
                        },
                        onDrag = { change, _ ->
                            current.add(change.position)
                            change.consume()
                        },
                        onDragEnd = {
                            if (current.isNotEmpty()) {
                                strokes.add(current.toList())
                                current.clear()
                                values[field.id] = "signature:${strokes.size}"
                                errors.remove(field.id)
                            }
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val drawStroke: (List<Offset>) -> Unit = { points ->
                    if (points.size > 1) {
                        val p = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                        drawPath(
                            path = p,
                            color = strokeColor,
                            style = Stroke(
                                width = strokeWidthDp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
                strokes.forEach(drawStroke)
                drawStroke(current.toList())
            }
            if (strokes.isEmpty() && current.isEmpty()) {
                Text(
                    text = field.placeholder?.interpolated() ?: "Sign here",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            strokes.clear()
            current.clear()
            values.remove(field.id)
        }) { Text(clearText) }
    }
}

// ─── helpers ─────────────────────────────────────────────────────────

internal fun formatNumber(value: Float, decimalPlaces: Int): String {
    return if (decimalPlaces <= 0) {
        value.roundToInt().toString()
    } else {
        "%.${decimalPlaces}f".format(value)
    }
}

internal fun formatNumber(value: Double, decimalPlaces: Int): String {
    return if (decimalPlaces <= 0) {
        value.roundToInt().toString()
    } else {
        "%.${decimalPlaces}f".format(value)
    }
}
