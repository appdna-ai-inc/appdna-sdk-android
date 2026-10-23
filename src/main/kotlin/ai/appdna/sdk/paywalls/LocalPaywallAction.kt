package ai.appdna.sdk.paywalls

import androidx.compose.runtime.compositionLocalOf

/**
 * #1076 — the host's `onPaywallAction`, reachable from anywhere inside a paywall.
 *
 * 🔴 A COMPOSITION LOCAL, NOT A PARAMETER, AND THE REASON IS A CRASH. Threading it as a parameter
 * through `PaywallScreen` worked everywhere except a real device, where the app died on launch:
 *
 *     java.lang.VerifyError: Verifier rejected class …PaywallActivityKt:
 *       void …PaywallScreen(PaywallConfig, Function2, Function0, Function1, Function1, Function2,
 *       boolean, Composer, int, int) failed to verify:
 *       [0xE95] copy1 v4<-v295 type=Reference: androidx.compose.runtime.Composer
 *
 * `PaywallScreen` is already enormous, and one more parameter pushed its generated method past
 * what D8's verifier accepts (`v295` — the register pressure is the tell). Every JVM and
 * Robolectric test stayed green, because the JVM verifier is not ART's. It is the same hazard this
 * SDK has hit before when a composable grew, and the only instrument that sees it is launching a
 * real paywall on a device.
 *
 * So the callback travels out-of-band. That also removes it from `PaywallSectionView`,
 * `PaywallExtraButtons` and `PaywallStickyFooter`, which were carrying it only to pass it on.
 *
 * Defaults to a no-op, so a preview or a test that provides nothing simply reports nothing.
 */
internal val LocalPaywallAction = compositionLocalOf<(PaywallAction) -> Unit> { {} }
