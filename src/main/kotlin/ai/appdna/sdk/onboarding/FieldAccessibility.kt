package ai.appdna.sdk.onboarding

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The name a text input is read by: its label as the author wrote it, else its placeholder. Trimmed; empty when the
 * author gave neither. A field's label is drawn as a separate text above it, so without this TalkBack read an unnamed
 * edit box once the placeholder had gone. iOS `FieldAccessibility.name`.
 */
internal fun fieldAccessibilityName(label: String?, placeholder: String?): String =
    listOf(label, placeholder).firstNotNullOfOrNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } } ?: ""

/** Names a text input for TalkBack ([fieldAccessibilityName]); nothing when the name is empty. */
internal fun Modifier.fieldName(name: String): Modifier =
    if (name.isEmpty()) this else this.semantics { contentDescription = name }
