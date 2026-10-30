package ai.appdna.sdk.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bundled-asset mapper, with the fall-through that makes it safe.
 *
 * 🔴 THE FALL-THROUGH IS THE POINT, not the rewrite. iOS's `BundledImageResolver` returns nil for
 * a file that is not in the bundle and the view goes on to the network; a mapper that rewrote
 * unconditionally would turn a PARTIALLY bundled config into broken images instead of slow ones —
 * strictly worse than not bundling at all. So "a missing asset maps to null" is the assertion that
 * matters most here, and it is the one a careless implementation passes by accident only if it
 * actually opens the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledAssetMapperTest {

    private lateinit var context: Context

    private val present = "appdna-assets/0123456789abcdef.png"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun options() = Options(context)

    /** The positive branch, with the existence check stubbed — see the seam's comment. */
    private fun withAsset(path: String) = BundledAssetMapper(context) { it == path }

    /** The REAL mapper: the default existence check, against the module's own asset tree. */
    private fun real() = BundledAssetMapper(context)

    @Test
    fun `a bundled asset maps to the android_asset uri`() {
        assertEquals(
            "file:///android_asset/$present",
            withAsset(present).map(present, options())?.toString(),
        )
    }

    /**
     * The REAL existence check, not the stub: nothing is bundled in this module, so every
     * `appdna-assets/` path must fall through. This is the branch that protects a partially
     * bundled config, and it has to open the file rather than guess.
     */
    @Test
    fun `an appdna-assets path that was NOT bundled falls through to the network`() {
        assertNull(
            "a missing asset must map to null so the remote URL is still tried",
            real().map("appdna-assets/deadbeefdeadbeef.png", options()),
        )
    }

    @Test
    fun `a remote url is left alone`() {
        assertNull(real().map("https://cdn.example.com/a.png", options()))
        // Even with the asset present, a remote URL is not ours to rewrite.
        assertNull(withAsset(present).map("https://cdn.example.com/a.png", options()))
    }

    /**
     * A bare relative path is not ours to claim — a host app's own loader may be composed with
     * this one, and swallowing every relative string would redirect theirs too.
     */
    @Test
    fun `a relative path without the prefix is left alone`() {
        val m = withAsset("images/a.png")
        assertNull(m.map("images/a.png", options()))
        assertNull(m.map("appdna-assets", options()))
    }
}
