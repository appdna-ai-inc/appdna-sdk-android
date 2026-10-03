package ai.appdna.sdk.onboarding

/**
 * The auth-action sets, in one place.
 *
 * - [delegateRequiredActions]: actions that REQUIRE a host-registered onboarding delegate
 *   (`onBeforeStepAdvance`) before they can advance. iOS source of truth:
 *   `OnboardingRenderer.swift` `AuthActionPolicy.delegateRequiredActions`. Android keeps
 *   `social_login` out of this set because it gates that action separately (same behaviour).
 * - [bridgeFloorActions]: the actions whose host hook a Flutter / React Native bridge must wait
 *   for at least [StepAdvanceResult.AUTH_BRIDGE_TIMEOUT_MS] — a sign-in spans OS UI the host cannot
 *   shorten. Equal to every wrapper's auth set (`check:auth-action-parity`).
 */
internal object AuthActionPolicy {
    val delegateRequiredActions = setOf(
        "login",
        "register",
        "reset_password",
        "magic_link",
        "verify_email",
        "resend_verification",
        "enable_biometric",
        "email_login",
        "request_otp",
        "verify_otp",
        "logout",
        "change_password",
        "set_new_password",
        "delete_account",
        "update_profile",
    )

    val bridgeFloorActions = delegateRequiredActions + "social_login"
}
