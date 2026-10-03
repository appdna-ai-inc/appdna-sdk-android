package ai.appdna.sdk.network

import ai.appdna.sdk.Environment
import ai.appdna.sdk.Log
import android.content.pm.ApplicationInfo
import android.os.Bundle
import java.net.URI

/**
 * TEST-ONLY base-URL override, so an E2E host can point a debug build at a local
 * server. Not public API and not documented for hosts.
 *
 * The `<application>` meta-data [BASE_URL_OVERRIDE_META_KEY] applies only when the app is
 * debuggable (`FLAG_DEBUGGABLE`) AND the environment is [Environment.SANDBOX]. A blank value counts
 * as absent; the value must parse as an `http://` / `https://` URL with a host, else it is logged
 * once and ignored; a trailing `/` is stripped. It covers the API and event ingest only.
 */
internal const val BASE_URL_OVERRIDE_META_KEY = "ai.appdna.sdk.BASE_URL_OVERRIDE"

@Volatile private var invalidOverrideLogged = false

internal fun resolveBaseUrl(appInfo: ApplicationInfo, metaData: Bundle?, env: Environment): String {
    val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable || env != Environment.SANDBOX) return env.baseUrl
    val raw = metaData?.getString(BASE_URL_OVERRIDE_META_KEY)?.trim()
    if (raw.isNullOrEmpty()) return env.baseUrl
    val valid = runCatching {
        val uri = URI(raw)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    }.getOrDefault(false)
    if (!valid) {
        if (!invalidOverrideLogged) {
            invalidOverrideLogged = true
            Log.warning("Ignoring $BASE_URL_OVERRIDE_META_KEY: not an http(s) URL with a host")
        }
        return env.baseUrl
    }
    return raw.trimEnd('/')
}
