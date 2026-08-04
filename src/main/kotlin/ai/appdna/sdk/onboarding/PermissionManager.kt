package ai.appdna.sdk.onboarding

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * SPEC-421 — Coarse, cross-type permission status the onboarding permission pipeline routes on.
 *
 * [UNAVAILABLE] means the request cannot be made safely (permission not declared in the host
 * manifest, or an unsupported/unknown type) — it is NEVER routed to [DENIED] (mirrors iOS's
 * missing-Info.plist-key semantics so `permission_unavailable` has a real Android emission site).
 */
enum class PermissionStatus {
    GRANTED,
    DENIED,
    UNDETERMINED,
    UNAVAILABLE,
}

/**
 * SPEC-421 — pure routing decision the pipeline derives from a [PermissionStatus].
 * Kept separate + pure so the status→action mapping is unit-testable without the OS.
 */
enum class PermissionRouteDecision {
    /** Already granted: emit `permission_already_granted`, store `granted`, advance (no prompt). */
    ALREADY_GRANTED,
    /** Denied: emit `permission_denied`, store `denied`, optional settings fallback, advance. */
    DENIED,
    /** Unavailable (undeclared / unsupported): emit `permission_unavailable`, advance, store nothing. */
    UNAVAILABLE,
    /** Undetermined: emit `permission_prompted`, request the OS prompt, then store the result. */
    PROMPT,
}

/**
 * SPEC-421 — runtime permission manager for onboarding permission steps (Android mirror of the
 * iOS `PermissionManager`).
 *
 * Two responsibilities:
 *  1. [status] — read the current authorization via `ContextCompat.checkSelfPermission`.
 *     Applies the manifest guard: a type whose runtime permission is NOT declared in the host
 *     manifest returns [PermissionStatus.UNAVAILABLE] (we never launch → the request can't silently
 *     no-op). `att` has no Android equivalent (auto-granted no-op) and `notification` below API 33 is
 *     auto-granted (no runtime permission exists there).
 *  2. [request] — fire the real OS prompt (via a [ActivityResultLauncher] bridge registered at
 *     composition by the caller) and return whether it was granted.
 *
 * The `ActivityResultLauncher` itself MUST be registered at composition (never lazily at button-tap,
 * which throws `IllegalStateException` when RESUMED); the caller wires [requestLauncher] to it and
 * completes the pending request via [completePending].
 */
class PermissionManager(private val context: Context) {

    /** Bridge to the composition-registered `ActivityResultLauncher`. Given a permission string, it
     * launches the OS prompt; the result arrives asynchronously via [completePending]. */
    var requestLauncher: ((String) -> Unit)? = null

    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Mrozu (alarmy s4.1) — bridge to a composition-registered `StartActivityForResult` launcher that
     * opens the ACTION_REQUEST_SCHEDULE_EXACT_ALARM Settings screen (SCHEDULE_EXACT_ALARM has no
     * runtime-permission dialog). The result arrives via [completePendingExactAlarm], which re-reads
     * [canScheduleExactAlarms] once the user returns. When null (no launcher wired) an `alarm` request
     * safe-falls-back to the current grant state without launching.
     */
    var exactAlarmSettingsLauncher: (() -> Unit)? = null

    private var pendingExactAlarm: CompletableDeferred<Boolean>? = null

    companion object {
        /** Whether [type] is a supported permission type at all. */
        fun isSupported(type: String): Boolean = when (type) {
            "notification", "att", "location", "camera",
            "microphone", "photos", "contacts", "calendar",
            // Mrozu (alarmy s4.1) — `alarm` = the SCHEDULE_EXACT_ALARM capability. Not a dangerous
            // runtime permission (no RequestPermission dialog); it's a Settings toggle reached via
            // ACTION_REQUEST_SCHEDULE_EXACT_ALARM on API 31+ and auto-granted below API 31. Routed
            // through [status]/[request] special-cases below. Parity with iOS PermissionManager.isSupported.
            "alarm" -> true
            // Mrozu QA (2026-08-04, Flo s26) — `health` (Health Connect) is a console-authorable
            // permission_type that already ROUTES through this manager (resolvePermissionType → here),
            // but the native Health Connect authorization request is DEFERRED (needs the
            // androidx.health.connect client dep + per-app read/write record-type set + the Health
            // Connect availability check, which is host infra). It falls to the `else` safe path so the
            // CTA advances instead of stranding the user. The standalone `health_connect` block is the
            // host-driven connect surface today. Parity with iOS PermissionManager.isSupported.
            else -> false
        }

        /**
         * The Android runtime permission string that MUST be granted for [type], given [sdkInt].
         * Returns `null` for types that need no runtime permission on this API level:
         *  - `att` — no Android equivalent (auto-granted no-op).
         *  - `notification` below API 33 — auto-granted (POST_NOTIFICATIONS didn't exist).
         *  - unknown/unsupported types.
         */
        fun androidPermission(type: String, sdkInt: Int): String? = when (type) {
            "notification" -> if (sdkInt >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
            "camera" -> Manifest.permission.CAMERA
            "microphone" -> Manifest.permission.RECORD_AUDIO
            "location" -> Manifest.permission.ACCESS_FINE_LOCATION
            "photos" -> if (sdkInt >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            "contacts" -> Manifest.permission.READ_CONTACTS
            "calendar" -> Manifest.permission.READ_CALENDAR
            "att" -> null // no Android equivalent
            // `alarm` (SCHEDULE_EXACT_ALARM) is NOT a dangerous runtime permission — it can't be
            // requested through RequestPermission. Returns null (no manifest gate); [status]/[request]
            // handle it via AlarmManager.canScheduleExactAlarms + the settings intent. Mrozu alarmy s4.1.
            "alarm" -> null
            else -> null
        }

        /** Notification auth is granted without a prompt below API 33 (no runtime permission existed). */
        fun notificationGrantedWithoutPrompt(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

        /**
         * Pure guard decision. Returns [PermissionStatus.UNAVAILABLE] when the type is unsupported, or
         * when it requires a runtime permission that is NOT declared in the host manifest. Returns
         * `null` to mean "safe to proceed". Injectable [permissionDeclared] keeps this unit-testable.
         */
        fun manifestGuardStatus(
            type: String,
            sdkInt: Int,
            permissionDeclared: (String) -> Boolean,
        ): PermissionStatus? {
            if (!isSupported(type)) return PermissionStatus.UNAVAILABLE
            val perm = androidPermission(type, sdkInt)
                ?: return null // supported + no runtime permission required (att, notification<33) → safe
            return if (permissionDeclared(perm)) null else PermissionStatus.UNAVAILABLE
        }

        /** Pure status → route mapping used by the pipeline. Extracted for OS-free unit testing. */
        fun route(status: PermissionStatus): PermissionRouteDecision = when (status) {
            PermissionStatus.GRANTED -> PermissionRouteDecision.ALREADY_GRANTED
            PermissionStatus.DENIED -> PermissionRouteDecision.DENIED
            PermissionStatus.UNAVAILABLE -> PermissionRouteDecision.UNAVAILABLE
            PermissionStatus.UNDETERMINED -> PermissionRouteDecision.PROMPT
        }

        /**
         * Sticky-denied detection is post-request only: `checkSelfPermission == DENIED` is identical for
         * never-asked vs permanently-denied. After a launch returns denied, `!shouldShowRationale` means
         * the OS won't prompt again → blocked (present the settings fallback). Pure + testable.
         */
        fun isBlockedAfterDenial(granted: Boolean, shouldShowRationale: Boolean): Boolean =
            !granted && !shouldShowRationale
    }

    /** The set of permissions declared in the host manifest (empty on any read failure). */
    private fun declaredPermissions(): Set<String> = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun guardStatus(type: String): PermissionStatus? {
        val declared = declaredPermissions()
        return manifestGuardStatus(type, Build.VERSION.SDK_INT) { it in declared }
    }

    // MARK: Status

    fun status(type: String): PermissionStatus {
        guardStatus(type)?.let { return it }

        val sdk = Build.VERSION.SDK_INT
        when (type) {
            // No Android equivalent — auto-granted no-op. Route through PROMPT so the request() path
            // fires and `permission_granted` is emitted (parity with iOS ATT<14.5).
            "att" -> return PermissionStatus.UNDETERMINED
            "notification" -> if (notificationGrantedWithoutPrompt(sdk)) return PermissionStatus.GRANTED
            // Mrozu (alarmy s4.1) — SCHEDULE_EXACT_ALARM. Already granted → GRANTED (no prompt); else
            // UNDETERMINED so request() opens the exact-alarm Settings screen. Below API 31 exact
            // alarms are always allowed → GRANTED.
            "alarm" -> return if (canScheduleExactAlarms()) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.UNDETERMINED
            }
        }

        val perm = androidPermission(type, sdk) ?: return PermissionStatus.UNDETERMINED
        return if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.GRANTED
        } else {
            // Pre-request we cannot distinguish never-asked from permanently-denied → prompt.
            PermissionStatus.UNDETERMINED
        }
    }

    // MARK: Request

    /**
     * Fire the real OS prompt (via the composition-registered launcher) and return whether granted.
     *  - `att` → auto-granted no-op (returns true, no launch).
     *  - `notification` below API 33 → auto-granted (returns true, no launch).
     *  - unsupported / no launcher wired → false.
     */
    suspend fun request(type: String): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (type == "att") return true
        if (type == "notification" && notificationGrantedWithoutPrompt(sdk)) return true
        if (type == "alarm") return requestExactAlarm()

        val perm = androidPermission(type, sdk) ?: return false
        val launcher = requestLauncher ?: return false

        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher(perm)
        return deferred.await()
    }

    /** Completes the in-flight [request] with the launcher's result. Called from the launcher callback. */
    fun completePending(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }

    // MARK: Exact-alarm (SCHEDULE_EXACT_ALARM) — Mrozu alarmy s4.1

    /**
     * Whether this app can schedule exact alarms right now. Below API 31 exact alarms are always
     * permitted; on API 31+ it reflects the SCHEDULE_EXACT_ALARM Settings toggle (or a granted
     * USE_EXACT_ALARM). Pure read — never launches anything.
     */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    /**
     * The Settings intent that opens the per-app "Alarms & reminders" (exact-alarm) toggle. Null below
     * API 31 (exact alarms are allowed by default there, so no toggle exists). Built here — not in the
     * composable — so the host layer stays a thin launcher registration.
     */
    fun exactAlarmSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    /**
     * Fire the exact-alarm request. If already granted → true (no launch). Otherwise open the
     * exact-alarm Settings screen via [exactAlarmSettingsLauncher] and await the user's return, at
     * which point [completePendingExactAlarm] re-reads [canScheduleExactAlarms]. No launcher wired
     * (e.g. running headless) → returns the current grant state without launching (safe fallback).
     */
    private suspend fun requestExactAlarm(): Boolean {
        if (canScheduleExactAlarms()) return true
        val launch = exactAlarmSettingsLauncher ?: return canScheduleExactAlarms()
        val deferred = CompletableDeferred<Boolean>()
        pendingExactAlarm = deferred
        launch()
        return deferred.await()
    }

    /**
     * Completes the in-flight [requestExactAlarm] by re-reading [canScheduleExactAlarms] once the user
     * returns from the ACTION_REQUEST_SCHEDULE_EXACT_ALARM Settings screen. Called from the
     * composition-registered `StartActivityForResult` callback.
     */
    fun completePendingExactAlarm() {
        pendingExactAlarm?.complete(canScheduleExactAlarms())
        pendingExactAlarm = null
    }

    /** Whether the OS would still show a rationale/prompt for [type] (false when permanently denied). */
    fun shouldShowRationale(type: String): Boolean {
        val activity = context as? Activity ?: return false
        val perm = androidPermission(type, Build.VERSION.SDK_INT) ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
    }

    // MARK: Settings deep-link

    /** Open the app's Settings details page so a permanently-denied permission can be flipped. */
    fun openAppSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            ai.appdna.sdk.Log.warning("[Permission] failed to open app settings: ${e.message}")
        }
    }
}
