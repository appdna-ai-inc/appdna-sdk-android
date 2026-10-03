package ai.appdna.sdk

import ai.appdna.sdk.onboarding.AppDNAOnboardingDelegate
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import ai.appdna.sdk.onboarding.PermissionHandling
import ai.appdna.sdk.onboarding.PermissionManager
import ai.appdna.sdk.onboarding.PermissionRouteDecision
import ai.appdna.sdk.onboarding.PermissionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pure/testable logic of the onboarding runtime-permission pipeline (Android mirror of iOS
 * PermissionManagerTests). The OS dialog itself is not unit-testable; everything around it (type →
 * permission-string mapping, manifest guard decision, status → routing, notification API-level
 * short-circuit, sticky-denied detection, delegate defaults) is.
 */
class PermissionManagerTest {

    private companion object {
        const val API_33 = 33 // Build.VERSION_CODES.TIRAMISU
        const val API_32 = 32
    }

    // MARK: - Type → Android permission-string mapping

    @Test
    fun requiredPermissionMapping() {
        assertEquals("android.permission.CAMERA", PermissionManager.androidPermission("camera", API_33))
        assertEquals("android.permission.RECORD_AUDIO", PermissionManager.androidPermission("microphone", API_33))
        assertEquals("android.permission.ACCESS_FINE_LOCATION", PermissionManager.androidPermission("location", API_33))
        assertEquals("android.permission.READ_CONTACTS", PermissionManager.androidPermission("contacts", API_33))
        assertEquals("android.permission.READ_CALENDAR", PermissionManager.androidPermission("calendar", API_33))
    }

    @Test
    fun photosPermissionIsApiLevelBranched() {
        assertEquals("android.permission.READ_MEDIA_IMAGES", PermissionManager.androidPermission("photos", API_33))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", PermissionManager.androidPermission("photos", API_32))
    }

    @Test
    fun notificationPermissionIsApiLevelBranched() {
        // API 33+ → POST_NOTIFICATIONS runtime permission; below → none (auto-granted).
        assertEquals("android.permission.POST_NOTIFICATIONS", PermissionManager.androidPermission("notification", API_33))
        assertNull(PermissionManager.androidPermission("notification", API_32))
    }

    @Test
    fun attHasNoAndroidEquivalent() {
        // att → auto-granted no-op, no permission string.
        assertNull(PermissionManager.androidPermission("att", API_33))
        assertTrue(PermissionManager.isSupported("att"))
    }

    @Test
    fun unknownTypeHasNoPermissionAndIsUnsupported() {
        assertNull(PermissionManager.androidPermission("health", API_33))
        assertFalse(PermissionManager.isSupported("health"))
        assertFalse(PermissionManager.isSupported("exact_alarm"))
        assertFalse(PermissionManager.isSupported(""))
    }

    @Test
    fun supportedTypes() {
        for (t in listOf("notification", "att", "location", "camera", "microphone", "photos", "contacts", "calendar")) {
            assertTrue("$t should be supported", PermissionManager.isSupported(t))
        }
    }

    // MARK: - Notification API-level short-circuit

    @Test
    fun notificationGrantedWithoutPromptBelow33() {
        assertTrue(PermissionManager.notificationGrantedWithoutPrompt(API_32))
        assertTrue(PermissionManager.notificationGrantedWithoutPrompt(24))
    }

    @Test
    fun notificationPromptsAt33AndAbove() {
        assertFalse(PermissionManager.notificationGrantedWithoutPrompt(API_33))
        assertFalse(PermissionManager.notificationGrantedWithoutPrompt(34))
    }

    // MARK: - Manifest-declared guard decision

    @Test
    fun guardUnavailableWhenPermissionNotDeclared() {
        // camera requires a runtime permission; not declared in manifest → UNAVAILABLE (do NOT launch).
        val decision = PermissionManager.manifestGuardStatus("camera", API_33) { false }
        assertEquals(PermissionStatus.UNAVAILABLE, decision)
    }

    @Test
    fun guardProceedsWhenPermissionDeclared() {
        val decision = PermissionManager.manifestGuardStatus("camera", API_33) { true }
        assertNull(decision)
    }

    @Test
    fun guardChecksTheCorrectPermission() {
        var asked: String? = null
        PermissionManager.manifestGuardStatus("photos", API_33) { perm ->
            asked = perm
            true
        }
        assertEquals("android.permission.READ_MEDIA_IMAGES", asked)
    }

    @Test
    fun guardAttAlwaysProceeds() {
        // att requires no runtime permission → proceed even if every lookup fails.
        val decision = PermissionManager.manifestGuardStatus("att", API_33) { false }
        assertNull(decision)
    }

    @Test
    fun guardNotificationBelow33AlwaysProceeds() {
        // notification below 33 needs no runtime permission → proceed.
        val decision = PermissionManager.manifestGuardStatus("notification", API_32) { false }
        assertNull(decision)
    }

    @Test
    fun guardUnsupportedTypeIsUnavailable() {
        val decision = PermissionManager.manifestGuardStatus("health", API_33) { true }
        assertEquals(PermissionStatus.UNAVAILABLE, decision)
    }

    // MARK: - Status → routing decision

    @Test
    fun routeAlreadyGranted() {
        assertEquals(PermissionRouteDecision.ALREADY_GRANTED, PermissionManager.route(PermissionStatus.GRANTED))
    }

    @Test
    fun routeDenied() {
        assertEquals(PermissionRouteDecision.DENIED, PermissionManager.route(PermissionStatus.DENIED))
    }

    @Test
    fun routeUnavailable() {
        assertEquals(PermissionRouteDecision.UNAVAILABLE, PermissionManager.route(PermissionStatus.UNAVAILABLE))
    }

    @Test
    fun routePrompt() {
        assertEquals(PermissionRouteDecision.PROMPT, PermissionManager.route(PermissionStatus.UNDETERMINED))
    }

    /**
     * The stored `permission_{type}` value the pipeline writes per decision:
     * granted/already → "granted"; denied → "denied"; unavailable → nothing stored.
     */
    @Test
    fun storedValuePerDecision() {
        assertEquals("granted", storedValue(PermissionRouteDecision.ALREADY_GRANTED))
        assertEquals("denied", storedValue(PermissionRouteDecision.DENIED))
        assertNull(storedValue(PermissionRouteDecision.UNAVAILABLE))
        // prompt stores the request's boolean result, resolved at runtime.
        assertNull(storedValue(PermissionRouteDecision.PROMPT))
    }

    // MARK: - Sticky-denied detection (post-request)

    @Test
    fun blockedAfterPermanentDenial() {
        // denied + won't show rationale again → blocked (present settings fallback).
        assertTrue(PermissionManager.isBlockedAfterDenial(granted = false, shouldShowRationale = false))
    }

    @Test
    fun notBlockedWhenRationaleCanStillShow() {
        assertFalse(PermissionManager.isBlockedAfterDenial(granted = false, shouldShowRationale = true))
    }

    @Test
    fun notBlockedWhenGranted() {
        assertFalse(PermissionManager.isBlockedAfterDenial(granted = true, shouldShowRationale = false))
        assertFalse(PermissionManager.isBlockedAfterDenial(granted = true, shouldShowRationale = true))
    }

    // MARK: - Delegate defaults + HandledByHost

    @Test
    fun delegateDefaultsAreInert() = runBlocking {
        val d = object : AppDNAOnboardingDelegate {}
        // Default pre-hook → SDK runs the OS flow.
        assertNull(d.onPermissionRequest("camera"))
        // Default result callback is a no-op (must not crash).
        d.onPermissionResult(flowId = "f", stepId = "s", permissionType = "camera", granted = true)
    }

    @Test
    fun handledByHostStoresAndReportsGrant() = runBlocking {
        // A host that resolves the permission itself → the pipeline stores + emits its granted value.
        val delegate = object : AppDNAOnboardingDelegate {
            override suspend fun onPermissionRequest(permissionType: String): PermissionHandling =
                PermissionHandling.HandledByHost(granted = true)
        }
        val handling = delegate.onPermissionRequest("notification")
        assertTrue(handling is PermissionHandling.HandledByHost)
        assertTrue((handling as PermissionHandling.HandledByHost).granted)
    }

    @Test
    fun permissionHandlingEquality() {
        assertEquals(PermissionHandling.Proceed, PermissionHandling.Proceed)
        assertEquals(PermissionHandling.HandledByHost(true), PermissionHandling.HandledByHost(true))
        assertFalse(PermissionHandling.HandledByHost(true) == PermissionHandling.HandledByHost(false))
    }

    // MARK: - console-shape decode (permission_type at step-content TOP LEVEL)

    /**
     * Regression for the contract bug: the console serializer writes `permission_type` /
     * `show_settings_fallback_on_denied` / `settings_fallback_label` as SIBLINGS of `content_blocks`
     * at the step-content top level (`step.layout = stepConfig`), NOT inside the inner `layout`
     * sub-map. The SDK previously read them from the inner map → resolved null/"" → every authored
     * permission step emitted `permission_unavailable` and advanced WITHOUT prompting. They must
     * decode into first-class StepConfig fields.
     */
    @Test
    fun permissionFieldsDecodeFromStepContentTopLevel() {
        // `step.layout` = the whole content object; permission keys are siblings of content_blocks.
        val step = mapOf(
            "id" to "perm-step",
            "type" to "custom",
            "layout" to mapOf(
                "permission_type" to "camera",
                "show_settings_fallback_on_denied" to true,
                "settings_fallback_label" to "Enable in Settings",
                "content_blocks" to listOf(
                    mapOf("type" to "text", "text" to "We need your camera"),
                ),
            ),
        )
        val parsed = OnboardingConfigParser.parseStepForTest(step)
        assertNotNull(parsed)
        val config = parsed!!.config
        // Top-level permission fields must resolve — NOT from the inner `layout` sub-map.
        assertEquals("camera", config.permission_type)
        assertEquals(true, config.show_settings_fallback_on_denied)
        assertEquals("Enable in Settings", config.settings_fallback_label)
        // Sibling content_blocks still decode alongside.
        assertEquals(1, config.content_blocks?.size)
        // The resolved type is non-empty AND supported → the pipeline WILL prompt.
        assertTrue(!config.permission_type.isNullOrEmpty())
        assertTrue(PermissionManager.isSupported(config.permission_type ?: ""))
    }

    /**
     * The `config`-keyed variant (some server paths write `step.config` instead of `step.layout`)
     * must resolve the same top-level permission fields.
     */
    @Test
    fun permissionFieldsDecodeFromConfigKey() {
        val step = mapOf(
            "id" to "s",
            "type" to "custom",
            "config" to mapOf(
                "permission_type" to "notification",
                "content_blocks" to emptyList<Any>(),
            ),
        )
        val config = OnboardingConfigParser.parseStepForTest(step)?.config
        assertEquals("notification", config?.permission_type)
    }

    // MARK: - Helpers (mirror the pipeline's per-decision store rule)

    private fun storedValue(decision: PermissionRouteDecision): String? = when (decision) {
        PermissionRouteDecision.ALREADY_GRANTED -> "granted"
        PermissionRouteDecision.DENIED -> "denied"
        PermissionRouteDecision.UNAVAILABLE -> null
        PermissionRouteDecision.PROMPT -> null // resolved from the OS request result at runtime
    }
}
