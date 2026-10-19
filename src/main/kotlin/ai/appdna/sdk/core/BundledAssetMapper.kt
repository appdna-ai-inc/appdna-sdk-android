package ai.appdna.sdk.core

import android.content.Context
import android.net.Uri
import coil.map.Mapper
import coil.request.Options
import java.io.IOException

/**
 * #986 — BUNDLED ASSETS, ON ANDROID.
 *
 * `scripts/bundle-assets.ts` walks a downloaded config bundle, fetches the images it references
 * into an `appdna-assets/` folder, and rewrites each URL to the relative path
 * `appdna-assets/<md5><ext>`. iOS's `BundledImageResolver` has loaded those from `Bundle.main`
 * since it shipped; Android had **no handler for the prefix at all**, so a bundled config's
 * rewritten URLs fetched nothing here and bundling was an iOS-only capability.
 *
 * Ship the folder inside `app/src/main/assets/` and this maps it to the `file:///android_asset/`
 * URI Coil already understands.
 *
 * 🔴 IT MUST FALL THROUGH, NOT FAIL. `BundledImageResolver` returns nil for a file that is not in
 * the bundle and the view goes on to the network; a mapper that rewrote unconditionally would turn
 * a PARTIALLY bundled config into broken images instead of slow ones — strictly worse than not
 * bundling at all. So the asset is opened before the path is rewritten, and an absent one is
 * handed back untouched for the remote fallback to pick up.
 *
 * 🔴 And only `appdna-assets/`. A bare relative path is not ours to claim: a host app's own
 * `ImageLoader` may be composed with this one, and a mapper that swallowed every relative string
 * would redirect theirs too.
 */
internal class BundledAssetMapper(
    private val context: Context,
    /*
     * Injectable ONLY so a unit test can assert the positive case. Robolectric serves the module's
     * real asset tree and the SDK must not ship a fake PNG inside its own AAR to make a test pass,
     * so the "it is there" branch needs a seam. The DEFAULT is the real `AssetManager`, and
     * `BundledAssetMapperTest` exercises that default for the negative case — which is the branch
     * that matters, and the one that must open the file rather than guess.
     */
    private val assetExists: (String) -> Boolean = { path -> opensFromAssets(context, path) },
) : Mapper<String, Uri> {

    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith(PREFIX)) return null
        if (!assetExists(data)) return null
        return Uri.parse("$ANDROID_ASSET_BASE$data")
    }

    internal companion object {
        const val PREFIX = "appdna-assets/"
        const val ANDROID_ASSET_BASE = "file:///android_asset/"

        /** The real check: open it, then close it. An absent asset throws rather than returning null. */
        private fun opensFromAssets(context: Context, path: String): Boolean = try {
            context.assets.open(path).close()
            true
        } catch (e: IOException) {
            // The one expected failure: the config references an asset this build did not bundle.
            false
        }
    }
}
