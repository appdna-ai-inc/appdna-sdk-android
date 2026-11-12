package ai.appdna.sdk.core

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role

/**
 * What a screen reader does with an image an author placed: an onboarding image block, a paywall image
 * section, a screen's image section, an in-app message's image, a survey question's image.
 *
 * An image whose authored description is blank is DECORATIVE: TalkBack skips it. An image with a description
 * is one node, read as that description with [Role.Image]. ONE rule; iOS `AuthoredImageAccessibility` is the
 * same, and the shared fixtures `dto_parsing/onboarding_image_alt`, `dto_parsing/paywall_image_alt` and
 * `dto_parsing/screen_image_alt` pin both.
 */
object AuthoredImageAccessibility {
    /** The description TalkBack reads, or null when the image is decorative (blank, whitespace, absent or not text). */
    fun label(alt: Any?): String? = (alt as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Applies [AuthoredImageAccessibility] to the image's own node, replacing whatever its content would expose — so
 * it holds while the image loads or fails as well as once it has drawn. `label` null: no semantics at all, the
 * node is skipped (decorative).
 */
fun Modifier.authoredImageSemantics(label: String?): Modifier =
    if (label == null) {
        clearAndSetSemantics { }
    } else {
        clearAndSetSemantics {
            contentDescription = label
            role = Role.Image
        }
    }
