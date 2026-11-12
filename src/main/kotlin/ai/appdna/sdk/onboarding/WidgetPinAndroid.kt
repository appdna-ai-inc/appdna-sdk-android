package ai.appdna.sdk.onboarding

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The production [WidgetPinPlatform]: the host app's own `AppWidgetManager`.
 *
 * The SDK runs in the host's process, so `context.packageName` is the host's, and the launcher only
 * pins a provider that belongs to the calling package.
 */
internal class AndroidWidgetPinPlatform(
    context: Context,
    override val sdkInt: Int = Build.VERSION.SDK_INT,
) : WidgetPinPlatform {
    private val app = context.applicationContext
    private val manager: AppWidgetManager? = runCatching { AppWidgetManager.getInstance(app) }.getOrNull()

    override fun homeScreenProviders(): List<String> {
        val m = manager ?: return emptyList()
        val pkg = app.packageName
        val infos: List<AppWidgetProviderInfo> = runCatching {
            if (sdkInt >= 26 && Build.VERSION.SDK_INT >= 26) {
                m.getInstalledProvidersForPackage(pkg, null)
            } else {
                m.installedProviders.filter { it.provider?.packageName == pkg }
            }
        }.getOrElse {
            ai.appdna.sdk.Log.warning("Add widget: could not list the app's widget providers: ${it.message}")
            emptyList()
        }
        return infos
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .mapNotNull { it.provider?.className }
    }

    override fun isRequestPinSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 && manager?.isRequestPinAppWidgetSupported == true

    override fun requestPin(providerClass: String, token: String): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val m = manager ?: return false
        WidgetPinTiming.mark("request")
        return m.requestPinAppWidget(ComponentName(app.packageName, providerClass), null, successCallback(token))
    }

    override fun widgetIds(providerClass: String): Set<Int>? = runCatching {
        manager?.getAppWidgetIds(ComponentName(app.packageName, providerClass))?.toSet()
    }.getOrNull()

    /**
     * The success callback. The launcher fires it ONLY on success, and a failed pin is never
     * reported. `FLAG_IMMUTABLE` is enough: detection is the id-set difference, so the SDK never
     * reads the `EXTRA_APPWIDGET_ID` the launcher would fill in.
     */
    private fun successCallback(token: String): PendingIntent {
        val intent = Intent(app, AppDNAWidgetPinReceiver::class.java)
            .setAction(AppDNAWidgetPinReceiver.ACTION_PINNED)
            .putExtra(AppDNAWidgetPinReceiver.EXTRA_TOKEN, token)
        return PendingIntent.getBroadcast(
            app, AppDNAWidgetPinReceiver.REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Receives the launcher's pin-success broadcast and hands it to the live attempt, through
 * [WidgetPinRegistry]. Declared in the library manifest with `exported="false"`: only a
 * PendingIntent this app created can reach it. A broadcast with no live attempt (the process died
 * while the dialog was up) is dropped — the widget is pinned, the SDK simply does not report it.
 */
internal class AppDNAWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetPinTiming.mark("callback")
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!WidgetPinRegistry.deliver(token)) {
            ai.appdna.sdk.Log.debug("Add widget: a pin succeeded with no live attempt to report it to")
        }
    }

    companion object {
        const val ACTION_PINNED = "ai.appdna.sdk.action.WIDGET_PINNED"
        const val EXTRA_TOKEN = "ai.appdna.sdk.extra.WIDGET_PIN_TOKEN"
        const val REQUEST_CODE = 0x5714
    }
}

/** The in-process route from [AppDNAWidgetPinReceiver] to the one live attempt. */
internal object WidgetPinRegistry {
    private var live: ((String?) -> Unit)? = null

    @Synchronized
    fun register(onCallback: (String?) -> Unit) {
        live = onCallback
    }

    @Synchronized
    fun unregister(onCallback: (String?) -> Unit) {
        if (live === onCallback) live = null
    }

    /** True when an attempt was listening; the attempt itself drops a token that is not its own. */
    fun deliver(token: String?): Boolean {
        val target = synchronized(this) { live } ?: return false
        if (Looper.myLooper() == Looper.getMainLooper()) target(token)
        else Handler(Looper.getMainLooper()).post { target(token) }
        return true
    }
}

/**
 * DEBUG-ONLY simulation of launcher behaviour, so a device walk can drive every branch.
 *
 * Read only when the host app is debuggable (`FLAG_DEBUGGABLE`, as the base-URL override is);
 * a release build never reads it. Set with
 *
 *     adb shell setprop debug.appdna.widget_pin_simulate <unsupported|refused|throws|silent|no_callback>
 *
 *  - `unsupported` → the launcher does not support pinning (`launcher_unsupported`);
 *  - `refused`     → `requestPinAppWidget` returns false (`request_refused`);
 *  - `throws`      → it throws `IllegalStateException` (`request_refused`);
 *  - `silent`      → it returns true and no dialog ever appears (`silent_launcher`);
 *  - `no_callback` → the REAL request is sent and the real dialog shows, but its success callback
 *    carries a token no attempt holds, so it is dropped as stale: the attempt returns from the dialog
 *    with no callback and the grace window (the widget-id set) has to decide — the launcher behaviour
 *    the platform allows ("if the pinning operation fails, your app isn't notified") and an emulator
 *    never shows, because there the callback always lands before the activity resumes.
 */
internal object WidgetPinSimulation {
    const val PROPERTY = "debug.appdna.widget_pin_simulate"
    val MODES = setOf("unsupported", "refused", "throws", "silent", "no_callback")
    const val WITHHELD_SUFFIX = "#withheld"

    fun mode(appInfo: ApplicationInfo, read: (String) -> String? = ::systemProperty): String? {
        if ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        val v = read(PROPERTY)?.trim()?.lowercase()
        return v?.takeIf { it in MODES }
    }

    fun wrap(real: WidgetPinPlatform, mode: String?): WidgetPinPlatform {
        if (mode == null) return real
        ai.appdna.sdk.Log.warning("Add widget: simulating a \"$mode\" launcher ($PROPERTY, debug build)")
        return object : WidgetPinPlatform by real {
            override fun isRequestPinSupported(): Boolean =
                if (mode == "unsupported") false else real.isRequestPinSupported()

            override fun requestPin(providerClass: String, token: String): Boolean = when (mode) {
                "refused" -> false
                "throws" -> throw IllegalStateException("simulated: $PROPERTY=throws")
                "silent" -> true
                "no_callback" -> real.requestPin(providerClass, token + WITHHELD_SUFFIX)
                else -> real.requestPin(providerClass, token)
            }
        }
    }

    private fun systemProperty(key: String): String? = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.trim().also { p.waitFor() }
    }.getOrNull()
}

/**
 * Timing marks for the timer measurement, logged under `AppDNA-E2E` in a DEBUGGABLE host only:
 * `request` (requestPinAppWidget sent), `pause` / `resume` (the activity), `callback` (the success
 * broadcast). The timer constants in [WidgetPinAttempt] were set from these.
 */
internal object WidgetPinTiming {
    @Volatile var enabled: Boolean = false

    fun mark(what: String) {
        if (!enabled) return
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E widget_pin $what t=${SystemClock.elapsedRealtime()}")
    }

    /** Each widget event with its properties, so a device walk can read what was reported. */
    fun event(name: String, properties: Map<String, Any>) {
        if (!enabled) return
        val props = properties.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
        android.util.Log.i("AppDNA-E2E", "AppDNA-E2E widget_event $name $props t=${SystemClock.elapsedRealtime()}")
    }
}

/** Main-thread timers for the production attempt. */
internal fun mainThreadSchedule(delayMs: Long, action: () -> Unit): WidgetPinTimer {
    val handler = Handler(Looper.getMainLooper())
    val r = Runnable { action() }
    handler.postDelayed(r, delayMs)
    return WidgetPinTimer { handler.removeCallbacks(r) }
}
