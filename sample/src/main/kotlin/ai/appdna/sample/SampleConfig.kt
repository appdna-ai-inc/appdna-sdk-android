package ai.appdna.sample

import android.content.Context
import android.content.pm.PackageManager

/**
 * Build-time sample settings, read from the manifest meta-data `sample/build.gradle.kts` fills in.
 * Null when not given (the default), so the literals at the call sites — the ones device walks
 * rewrite — still apply. A build for a Play testing track is given a real sandbox key and paywall id
 * (`-PappdnaSampleApiKey`, `-PappdnaSamplePaywallId`), because a release build talks to production.
 */
internal object SampleConfig {
    private fun meta(context: Context, key: String): String? = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(key)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun apiKey(context: Context): String? = meta(context, "ai.appdna.sample.API_KEY")

    fun paywallId(context: Context): String? = meta(context, "ai.appdna.sample.PAYWALL_ID")
}
