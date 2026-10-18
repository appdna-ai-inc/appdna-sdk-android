package ai.appdna.sdk

import ai.appdna.sdk.paywalls.LocalPaywallPageHex
import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallSectionView
import ai.appdna.sdk.paywalls.pageBackgroundHex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #954, #955 — DOES THE SOCIAL-PROOF SECTION DRAW ITS CAPTION AND ITS AUTHOR? Rendered, compared.
 *
 * The Android half of `SocialProofRenderTests.swift`, built the same way and for the same reason:
 * two fields decoded, travelled the wire and reached no view — `text` on the `app_rating` variant
 * (#954) and `author_name` anywhere (#955). Reading a renderer is how they got there; the only
 * statement worth making is about what ends up on screen.
 *
 * 🔴 NOT a golden snapshot, and not Roborazzi. A golden answers "does this match what we
 * recorded", which is a question about a file — and a recorded golden of a blank section would
 * have passed happily for as long as the field was unread. These compare two LIVE renders of the
 * same section with one field changed, which is the question the issue asks.
 *
 * 🔴 TWO HARNESSES WERE TRIED BEFORE THIS ONE, and both failures are worth keeping:
 *
 * - `captureRoboImage` writes NOTHING outside record mode, so under a plain `:testDebugUnitTest`
 *   every test failed with `FileNotFoundException`. Honest, but it made the test depend on which
 *   gradle task invoked it. Record mode is therefore forced ON here, in `@Before`.
 * - `ComposeTestRule` + `captureToImage()` reads pixels straight off the tree and looked like the
 *   cleaner answer — but every test timed out with
 *   `ComposeTimeoutException: Condition still not satisfied after 2000 ms`, because a paywall
 *   section composes under an entry animation and the tree never reports idle. Driving the clock
 *   by hand did not help: the capture itself waits. Roborazzi has its own idling strategy and
 *   does not.
 *
 * 🔴 EVERY ASSERTION HAS ITS CONTROL. "Adding the caption changed the screen" means nothing unless
 * this harness can also be shown NOT changing the screen for a key no renderer reads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SocialProofRenderTest {

    private fun paywallMap(data: Map<String, Any>, page: String = "#FFFFFF"): Map<String, Any> = mapOf(
        "id" to "pw_social_proof",
        "name" to "Social proof",
        "background" to mapOf("type" to "color", "value" to page),
        "sections" to listOf(mapOf("id" to "sec_sp", "type" to "social_proof", "data" to data)),
    )

    /**
     * Render one section to a PNG and return its BYTES, so two renders can be compared directly.
     *
     * The file name is derived from the content, so a repeated render reuses its own frame and two
     * different sections never collide.
     */
    private fun render(data: Map<String, Any>, page: String = "#FFFFFF"): ByteArray {
        val config = PaywallConfigParser.parseSinglePaywall("pw_social_proof", paywallMap(data, page))
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "social_proof" }
        val path = "build/tmp/social-proof-render/${(data.toString() + page).hashCode()}.png"
        File(path).parentFile?.mkdirs()
        captureRoboImage(path) {
            /*
             * 🔴 THE PAGE COLOUR HAS TO BE PROVIDED, or this harness measures itself.
             *
             * `PaywallScreen` provides `LocalPaywallPageHex` around every section, and the
             * app_rating text reads it to decide whether it should be dark or light. Composing
             * `PaywallSectionView` on its own leaves the local NULL, so the text fell back to
             * white and the ink count came back 0 ON THE FIXED CODE — a red that was about the
             * test, not the renderer. The value comes from `config.pageBackgroundHex()`, the same
             * production function the screen calls, rather than from the `page` string here.
             */
            CompositionLocalProvider(LocalPaywallPageHex provides config.pageBackgroundHex()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(android.graphics.Color.parseColor(page)))
                    .padding(16.dp),
            ) {
                PaywallSectionView(
                    section = section,
                    config = config,
                    selectedPlanId = null,
                    isPurchasing = false,
                    onPlanSelect = {},
                    onCTATap = {},
                    onRestore = {},
                    loc = { _, fallback -> fallback },
                )
            }
            }
        }
        val f = File(path)
        check(f.exists()) {
            "no frame was written to $path — Roborazzi is disabled, so every comparison below " +
                "would be about two missing files rather than two renders"
        }
        return f.readBytes()
    }

    @Before
    fun enableCapture() {
        // Roborazzi writes only in record mode, and this test COMPARES its own frames rather than
        // verifying goldens — so it turns recording on for itself instead of depending on the
        // gradle task that happened to invoke it.
        System.setProperty("roborazzi.test.record", "true")
    }

    private val rated = mapOf("sub_type" to "app_rating", "rating" to 4.8, "review_count" to 18240)

    // ── #954: the caption ────────────────────────────────────────────────────────────────────

    @Test
    fun `the caption is drawn on the app_rating variant`() {
        val withCaption = render(rated + ("text" to "Loved by 12,000 runners"))
        val without = render(rated)
        val otherCaption = render(rated + ("text" to "A completely different caption"))

        assertFalse(
            "authoring `text` on an app_rating section must CHANGE the screen — identical renders "
                + "mean the caption reached no view, which is #954 verbatim",
            withCaption.contentEquals(without),
        )
        assertFalse(
            "and the CONTENT must be what changed it — if any two captions render the same, this "
                + "harness is seeing a layout shift rather than the words",
            withCaption.contentEquals(otherCaption),
        )
    }

    @Test
    fun `the harness does not react to a field nothing reads`() {
        assertArrayEquals(
            "THE CONTROL FAILED: this harness reports a difference for a key no renderer reads, so "
                + "every 'the screen changed' assertion here is meaningless",
            render(rated),
            render(rated + ("not_a_field" to "xyz")),
        )
    }

    // ── #955: the author ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the author is drawn under the quote`() {
        val quote = mapOf("sub_type" to "app_rating", "testimonial" to "Two weeks in and I have not missed a day")
        assertFalse(
            "authoring `author_name` must CHANGE the screen — the generator has been writing an "
                + "author all along and this section drew nothing for it (#955)",
            render(quote + ("author_name" to "Sofia R.")).contentEquals(
                render(quote),
            ),
        )
    }

    @Test
    fun `an author with no quote draws nothing`() {
        assertArrayEquals(
            "an attribution with nothing to attribute must not appear on its own",
            render(rated),
            render(rated + ("author_name" to "Sofia R.")),
        )
    }

    // ── #947's defect, on the platform #947 did not touch ────────────────────────────────────

    /**
     * How many pixels are DARK — luminance under a quarter, i.e. ink rather than page or stars.
     *
     * 🔴 WHY A COLOUR MEASUREMENT AND NOT A BITMAP DIFF. The first two versions of the light-page
     * test compared renders with and without the text and asserted they differ — and BOTH passed
     * on the broken code, because adding a `Text` changes the section's HEIGHT whether or not a
     * single glyph is visible. The bitmaps differed for a layout reason while the words were
     * white-on-white, which is the exact defect dressed as a pass. Counting ink cannot be
     * satisfied by a layout shift: white text on a white page adds no dark pixels, however much
     * taller it makes the section.
     *
     * 🔴 THE THRESHOLD IS ABOUT COMPOSITED luminance, not the authored colour, and the first
     * version got that wrong: `#111827` at 70% alpha over white composites to ≈ 88, so a limit of
     * 64 counted ZERO ink on correct code and read as the defect. The three things on this screen
     * separate cleanly at 140 — ink ≈ 88, the gold star `#FBBF24` ≈ 193, the page 255.
     */
    private fun darkPixels(png: ByteArray): Int {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        var dark = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(p) < 128) continue
                val lum = 0.2126 * android.graphics.Color.red(p) +
                    0.7152 * android.graphics.Color.green(p) +
                    0.0722 * android.graphics.Color.blue(p)
                if (lum < 140) dark += 1
            }
        }
        return dark
    }

    @Test
    fun `the quote and the count are readable on a light page`() {
        /*
         * Both were `Color.White.copy(alpha = 0.7f)` — white at 70% over a WHITE page is nothing,
         * so neither line was there on a light paywall. iOS retired the same constant when #947
         * measured 1,557 pixels changing between appearances; Android kept it, so the two
         * platforms disagreed about a line both of them draw.
         */
        val stars = mapOf("sub_type" to "app_rating", "rating" to 4.8)
        val starsOnly = darkPixels(render(stars, page = "#FFFFFF"))
        val withCount = darkPixels(render(stars + ("review_count" to 18240), page = "#FFFFFF"))
        val withQuote = darkPixels(
            render(stars + ("testimonial" to "Two weeks in and I have not missed a day"), page = "#FFFFFF"),
        )

        assertTrue(
            "the REVIEW COUNT must put INK on a white page — it added ${withCount - starsOnly} dark "
                + "pixels over the $starsOnly the stars alone draw, which is white-on-white",
            withCount > starsOnly + 100,
        )
        assertTrue(
            "and so must the QUOTE — it added ${withQuote - starsOnly} dark pixels",
            withQuote > starsOnly + 300,
        )
        assertTrue(
            "THE CONTROL FAILED: the stars alone should put almost no ink on a white page "
                + "(they are gold), and they drew $starsOnly dark pixels — so this measurement is "
                + "counting something other than text",
            starsOnly < 100,
        )
    }
}
