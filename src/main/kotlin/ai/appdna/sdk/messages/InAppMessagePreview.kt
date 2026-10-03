package ai.appdna.sdk.messages

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * Compose `@Preview` functions for the in-app message
 * renderer. Designers can iterate on banner / modal / fullscreen layouts
 * inside Android Studio without running the SDK on a device.
 *
 * Production [InAppMessageView] signature is unchanged. Fixtures are
 * built via the existing public [MessageConfig] constructor and rendered
 * with no-op CTA / dismiss callbacks.
 *
 * Layouts covered: BANNER (top), MODAL (centered), FULLSCREEN.
 * Each comes in Light + Dark variants.
 */

// MARK: - Fixtures

private val previewTriggerRules = TriggerRules(event = "preview_event")

private fun bannerMessage(): MessageConfig = MessageConfig(
    name = "Preview Banner",
    message_type = MessageType.BANNER,
    content = MessageContent(
        title = "New feature available",
        body = "Try the redesigned home screen — it's faster and lighter.",
        cta_text = "Try it",
        dismiss_text = "Dismiss",
        banner_position = "top",
        background_color = "#FFFFFF",
        text_color = "#0F172A",
        button_color = "#6366F1",
        button_text_color = "#FFFFFF",
        corner_radius = 12,
    ),
    trigger_rules = previewTriggerRules,
)

private fun modalMessage(): MessageConfig = MessageConfig(
    name = "Preview Modal",
    message_type = MessageType.MODAL,
    content = MessageContent(
        title = "You're on a streak!",
        body = "5 days in a row. Keep it up to unlock a free month of Pro.",
        cta_text = "Claim reward",
        secondary_cta_text = "Maybe later",
        dismiss_text = "No thanks",
        background_color = "#FFFFFF",
        text_color = "#0F172A",
        button_color = "#10B981",
        button_text_color = "#FFFFFF",
        corner_radius = 16,
    ),
    trigger_rules = previewTriggerRules,
)

private fun fullscreenMessage(): MessageConfig = MessageConfig(
    name = "Preview Fullscreen",
    message_type = MessageType.FULLSCREEN,
    content = MessageContent(
        title = "Welcome to v2",
        body = "We've rebuilt the experience from the ground up. " +
            "Faster, simpler, and now with offline mode.",
        cta_text = "Take the tour",
        secondary_cta_text = "Skip",
        background_color = "#0F172A",
        text_color = "#FFFFFF",
        button_color = "#6366F1",
        button_text_color = "#FFFFFF",
        corner_radius = 14,
    ),
    trigger_rules = previewTriggerRules,
)

// MARK: - Frame

@Composable
private fun MessagePreviewFrame(
    isDark: Boolean,
    body: @Composable () -> Unit,
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tint a backdrop behind the message so dark-mode previews
                // read as actually dark inside Studio's preview pane.
                .background(if (isDark) Color(0xFF0B0F1A) else Color(0xFFE5E7EB))
        ) {
            body()
        }
    }
}

// MARK: - Banner

@Preview(name = "Message Banner — Light", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun MessageBannerLightPreview() {
    MessagePreviewFrame(isDark = false) {
        InAppMessageView(
            config = bannerMessage(),
            onCTATap = { },
            onDismiss = { },
            isDark = false,
        )
    }
}

@Preview(
    name = "Message Banner — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 200,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessageBannerDarkPreview() {
    MessagePreviewFrame(isDark = true) {
        InAppMessageView(
            config = bannerMessage().copy(
                content = bannerMessage().content.copy(
                    background_color = "#1F2937",
                    text_color = "#F9FAFB",
                ),
            ),
            onCTATap = { },
            onDismiss = { },
            isDark = true,
        )
    }
}

// MARK: - Modal (centered)

@Preview(name = "Message Modal — Light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun MessageModalLightPreview() {
    MessagePreviewFrame(isDark = false) {
        InAppMessageView(
            config = modalMessage(),
            onCTATap = { },
            onDismiss = { },
            isDark = false,
        )
    }
}

@Preview(
    name = "Message Modal — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessageModalDarkPreview() {
    MessagePreviewFrame(isDark = true) {
        InAppMessageView(
            config = modalMessage().copy(
                content = modalMessage().content.copy(
                    background_color = "#1F2937",
                    text_color = "#F9FAFB",
                ),
            ),
            onCTATap = { },
            onDismiss = { },
            isDark = true,
        )
    }
}

// MARK: - Fullscreen

@Preview(name = "Message Fullscreen — Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun MessageFullscreenLightPreview() {
    MessagePreviewFrame(isDark = false) {
        InAppMessageView(
            config = fullscreenMessage().copy(
                content = fullscreenMessage().content.copy(
                    background_color = "#FFFFFF",
                    text_color = "#0F172A",
                ),
            ),
            onCTATap = { },
            onDismiss = { },
            isDark = false,
        )
    }
}

@Preview(
    name = "Message Fullscreen — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessageFullscreenDarkPreview() {
    MessagePreviewFrame(isDark = true) {
        InAppMessageView(
            config = fullscreenMessage(),
            onCTATap = { },
            onDismiss = { },
            isDark = true,
        )
    }
}
