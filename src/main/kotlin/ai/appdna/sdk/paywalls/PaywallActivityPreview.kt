package ai.appdna.sdk.paywalls

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Compose `@Preview` functions for the paywall renderer.
 *
 * Designers can iterate on paywall visuals inside Android Studio without
 * running the SDK on a device. These previews call the public
 * [PaywallScreen] composable with hand-crafted in-memory [PaywallConfig]
 * fixtures so we don't depend on Firestore parsing — no network, no
 * `PaywallManager` setup, no callbacks beyond no-op stubs.
 *
 * Layout covered:
 *   header  → eye-catching title + subtitle
 *   features → check-marked feature list
 *   plans   → 2-plan grid (monthly + yearly with savings badge)
 *   sticky_footer → CTA + restore link pinned to bottom
 *
 * Production composable signatures are NOT changed by this file. The
 * fixture builder lives entirely in this file and is `private`.
 */
private fun previewPaywallConfig(): PaywallConfig {
    val plans = persistentListOf(
        PaywallPlan(
            id = "monthly",
            product_id = "com.example.monthly",
            name = "Monthly",
            price = "$9.99",
            period = "month",
            label = "Monthly",
            price_display = "$9.99 / mo",
            sort_order = 0,
        ),
        PaywallPlan(
            id = "yearly",
            product_id = "com.example.yearly",
            name = "Yearly",
            price = "$59.99",
            period = "year",
            badge = "Best value",
            is_default = true,
            label = "Yearly",
            price_display = "$59.99 / yr",
            sort_order = 1,
            savings_text = "Save 50%",
        ),
    )

    val features = listOf(
        "Unlimited workouts",
        "Personalised plans",
        "Offline access",
        "Cancel anytime",
    ).toImmutableList()

    val sections = listOf(
        PaywallSection(
            type = "header",
            id = "header",
            data = PaywallSectionData(
                title = "Unlock Pro",
                subtitle = "Faster results. Real coaches. Zero ads.",
            ),
        ),
        PaywallSection(
            type = "features",
            id = "features",
            data = PaywallSectionData(features = features),
        ),
        PaywallSection(
            type = "plans",
            id = "plans",
            data = PaywallSectionData(
                plans = plans,
                plan_display_style = "vertical_stack",
            ),
        ),
        PaywallSection(
            type = "sticky_footer",
            id = "footer",
            data = PaywallSectionData(
                cta_text = "Start free trial",
                cta_bg_color = "#6366F1",
                cta_text_color = "#FFFFFF",
                cta_corner_radius = 12f,
                secondary_text = "Restore purchases",
                secondary_action = "restore",
                legal_text = "Cancel anytime in Play Store.",
            ),
        ),
    ).toImmutableList()

    return PaywallConfig(
        id = "preview-paywall",
        name = "Preview Paywall",
        layout = PaywallLayout(type = "stack", spacing = 16f, padding = 20f),
        sections = sections,
        background = PaywallBackground(type = "color", color = "#0F172A"),
    )
}

/**
 * Preview wrapper. The renderer reads
 * `isSystemInDarkTheme()` for content-block resolution; we override via the
 * `isDark` parameter so each preview pins its scheme regardless of the
 * Studio host theme.
 */
@Composable
private fun PaywallPreviewBody(isDark: Boolean) {
    MaterialTheme {
        // Tint the canvas behind the paywall so the dark-mode preview reads
        // as actually dark in Android Studio's preview pane (the renderer's
        // own background is opaque, but the surrounding frame is not).
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) Color(0xFF0B0F1A) else Color(0xFFF8FAFC))
        ) {
            PaywallScreen(
                config = previewPaywallConfig(),
                onPlanSelected = { _, _ -> },
                onRestore = { },
                onDismiss = { },
                onPromoCodeSubmit = null,
                isDark = isDark,
            )
        }
    }
}

@Preview(name = "Paywall — Light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PaywallScreenLightPreview() {
    PaywallPreviewBody(isDark = false)
}

@Preview(
    name = "Paywall — Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PaywallScreenDarkPreview() {
    PaywallPreviewBody(isDark = true)
}
