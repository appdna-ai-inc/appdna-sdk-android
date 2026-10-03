package ai.appdna.sdk.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [AppDNAImageLoader], the Coil loader behind [NetworkImage].
 *
 * ## What this file used to be
 *
 * Three `@Ignore`d tests whose bodies were COMMENTED OUT, plus one live test that could not fail:
 *
 *     assertNotNull(AppDNAImageLoader)                                        // an `object`. Never null.
 *     assertEquals("ai.appdna.sdk.core.AppDNAImageLoader", …javaClass.name)   // a class names itself.
 *
 * Both assertions are true of a Kotlin `object` by construction — no edit to `AppDNAImageLoader` could
 * make either one red. It was a test in the sense that it had a `@Test` annotation. Meanwhile the two
 * things the loader actually promises went unchecked, and both are real bugs that ship silently:
 *
 *   - `singleton()` returning a NEW `ImageLoader` per call. Everything still renders; the memory cache
 *     and the 50 MB disk cache are simply never reused, so every recomposition re-downloads. The bug
 *     is invisible except as "the app feels slow and burns data".
 *   - `diskCacheDir()` pointing somewhere other than `<cacheDir>/appdna_image_cache` — into the app's
 *     own data, or a directory the OS never reclaims.
 *
 * The Roborazzi harness the old comments were waiting for is not needed for either: Robolectric was
 * already on the `testImplementation` classpath the whole time. So this now asserts the behaviour,
 * under Robolectric, for real. Falsified by planting both bugs — each turns a test red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkImageInstrumentationStubTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `singleton returns a usable ImageLoader`() {
        assertNotNull(AppDNAImageLoader.singleton(context()))
    }

    @Test
    fun `singleton returns the SAME instance across calls`() {
        val first = AppDNAImageLoader.singleton(context())
        val second = AppDNAImageLoader.singleton(context())
        // assertSame, not assertEquals: a fresh ImageLoader per call is the bug, and two fresh
        // loaders could plausibly compare equal. Identity is the property that matters — it is what
        // makes the memory + disk caches shared rather than per-call.
        assertSame("singleton() must hand back one shared ImageLoader — a new one per call silently disables caching", first, second)
    }

    @Test
    fun `singleton is stable even when called with different Context objects`() {
        // Real callers pass whatever Context they have (LocalContext.current — an Activity, per
        // composition). The loader keys off applicationContext, so the instance must not vary.
        val fromApp = AppDNAImageLoader.singleton(context())
        val fromOther = AppDNAImageLoader.singleton(context().applicationContext)
        assertSame(fromApp, fromOther)
    }

    @Test
    fun `disk cache directory is appdna_image_cache under cacheDir`() {
        val ctx = context()
        val expected = File(ctx.cacheDir, "appdna_image_cache")
        assertEquals(expected.absolutePath, AppDNAImageLoader.diskCacheDir(ctx).absolutePath)
    }
}
