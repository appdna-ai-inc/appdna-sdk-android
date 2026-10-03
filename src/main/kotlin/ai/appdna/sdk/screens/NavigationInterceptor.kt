package ai.appdna.sdk.screens

// Application.ActivityLifecycleCallbacks, wired in
// AppDNA.configure() via NavigationInterceptorActivityCallbacks. The public
// `AppDNA.notifyScreenAppeared(screenName)` API exists for Compose-only
// screens (no Activity per screen).

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-screen interception hook list. Mirrors iOS
 * `Screens/NavigationInterceptor.swift:5-58`.
 *
 * Architecture:
 * - Activity-driven path: `Application.registerActivityLifecycleCallbacks`
 *   wired in `AppDNA.configure()` invokes `evaluateInterceptions(activity.javaClass.simpleName)`
 *   on `onActivityResumed`. Equivalent of iOS' `viewDidAppear` swizzle.
 * - Compose-only path: hosts call `AppDNA.notifyScreenAppeared("Home")`
 *   from `NavHost` route changes (composables don't have an Activity
 *   per screen). Same `evaluateInterceptions` runs.
 *
 * Hooks are stored per-screen-name. Registering the same screen name
 * twice replaces the prior hook (iOS parity).
 */
class NavigationInterceptor private constructor() {

    companion object {
        @JvmStatic
        val shared: NavigationInterceptor = NavigationInterceptor()
    }

    private val hooks: CopyOnWriteArrayList<InterceptionHook> = CopyOnWriteArrayList()
    private val scope: CoroutineScope = MainScope()

    /**
     * Register a hook that fires when `screenName` appears. Replaces any
     * prior hook for the same name.
     */
    fun registerHook(screenName: String, evaluate: suspend (String) -> InterceptionResult) {
        // Replace existing hook for the same screen name.
        hooks.removeAll { it.screenName == screenName }
        hooks.add(InterceptionHook(screenName, evaluate))
    }

    /**
     * Remove the hook for `screenName` if any.
     */
    fun unregisterHook(screenName: String) {
        hooks.removeAll { it.screenName == screenName }
    }

    /**
     * Evaluate all matching hooks in registration order. Returns the
     * first non-`Allow` result, or `Allow` if every hook permits the
     * navigation. Returns `null` only when consent has been revoked.
     *
     * Suspend function — hook bodies may call into network / Firestore.
     * Must be invoked from a coroutine (e.g.
     * `lifecycleScope.launch { NavigationInterceptor.shared.evaluateInterceptions(...) }`).
     */
    suspend fun evaluateInterceptions(screenName: String): InterceptionResult? {
        if (!AppDNA.isConsentGranted()) return null

        // Skip SDK-internal screens (mirrors iOS swizzle filter at
        // `NavigationInterceptor.swift:40-43`).
        if (screenName.startsWith("AppDNA") ||
            screenName.startsWith("Screen") ||
            screenName.contains("Hosting")
        ) {
            return InterceptionResult.Allow
        }

        for (hook in hooks) {
            if (!matchesScreenName(hook.screenName, screenName)) continue
            val result = try {
                hook.evaluate(screenName)
            } catch (e: Throwable) {
                Log.warning("NavigationInterceptor: hook for ${hook.screenName} threw: ${e.message}")
                continue
            }
            if (result !is InterceptionResult.Allow) {
                return result
            }
        }

        // Also let `ScreenManager` evaluate "after" timing interceptions
        // configured server-side via `screen_index.interceptions[*]`. Same
        // call site iOS uses (`ScreenManager.shared.evaluateInterceptions`).
        ScreenManager.shared.evaluateInterceptions(screenName, timing = "after")

        return InterceptionResult.Allow
    }

    /**
     * Compose-only manual notification path. Equivalent of iOS swizzle
     * triggering `viewDidAppear`.
     */
    fun notifyScreenAppeared(screenName: String) {
        // Fire-and-forget on the SDK main scope. Hosts that need the
        // result should call `evaluateInterceptions` directly from a
        // coroutine they own.
        scope.launch {
            evaluateInterceptions(screenName)
        }
    }

    private fun matchesScreenName(pattern: String, candidate: String): Boolean {
        if (pattern == candidate) return true
        if (pattern == "*") return true
        // Glob support so hosts can register `LoginActivity*` etc.
        if (pattern.endsWith("*") && candidate.startsWith(pattern.dropLast(1))) return true
        if (pattern.startsWith("*") && candidate.endsWith(pattern.drop(1))) return true
        return false
    }
}

/**
 * Pair of a screen-name pattern and its evaluator. Suspend functions
 * because evaluators may call network / Firestore (e.g. fetch a
 * server-driven screen config before deciding whether to intercept).
 */
data class InterceptionHook(
    val screenName: String,
    val evaluate: suspend (String) -> InterceptionResult,
)

/**
 * Sum type matching iOS shape:
 * - `Allow` — proceed with the host's intended navigation.
 * - `Replace(screenId)` — show this server-driven screen instead.
 * - `Block(reason)` — reject the navigation entirely.
 */
sealed class InterceptionResult {
    object Allow : InterceptionResult()
    data class Replace(val screenId: String) : InterceptionResult()
    data class Block(val reason: String) : InterceptionResult()
}

/**
 * Activity-lifecycle adapter that bridges `onActivityResumed` to the
 * shared `NavigationInterceptor`. Registered in `AppDNA.configure()` —
 * see file-top wiring TODO.
 */
class NavigationInterceptorActivityCallbacks(
    private val interceptor: NavigationInterceptor,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        interceptor.notifyScreenAppeared(activity.javaClass.simpleName)
        // Track top-most resumed Activity so AppDNA.showPaywall(id) (and
        // other shorthand routes) can present without needing an explicit
        // Activity arg from the SDUI action dispatcher.
        ai.appdna.sdk.AppDNA.setTopActivity(activity)
    }
}

