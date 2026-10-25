package ai.appdna.sdk.paywalls

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/**
 * #1110 — A PAYWALL'S PURCHASE CELEBRATION IS DRAWN, ON TOP OF THE PAYWALL, ON ANDROID.
 *
 * #1109 made `particle_effect` storable and published, and iOS proved the pixels on a device after
 * a real StoreKit purchase. Android could not: the build Mac's emulator has no Play billing, so no
 * purchase there ever SUCCEEDS, and the celebration fires only on success. Decode (the shared
 * fixture) and trigger (#1103's `signalPurchaseCelebrate` from `handlePostPurchaseSuccess`) were
 * proven; the frame was not — and the one risk named on the issue is a paywall-specific
 * composition difference, the confetti drawn UNDER the paywall's own layers.
 *
 * So the frame is produced here instead (Rule 25): the real `PaywallScreen`, a paywall in the
 * published shape with confetti authored in `#34C759` — a green nothing else on it draws — the
 * production success signal (the call `handlePostPurchaseSuccess` makes), the clock advanced by
 * hand, and the composed pixels read back. Two questions, each with its control in the same file:
 *
 *   1. is the authored colour on screen after a success — and absent before it, and absent with
 *      the effect cleared (the same render, one field removed)?
 *   2. is it drawn OVER the paywall's content — inside the plan card's bounds, where the card's
 *      own grey background would hide anything composed beneath it?
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallPurchaseCelebrationDrawsTest {

    @get:Rule
    val compose = createComposeRule()

    private val planLabel = "Annual access"
    private var frame = 0
    /** One prefix per test, so the control's frames never overwrite the celebration's. */
    private var run = "unset"

    @Before
    fun enableCapture() {
        // Roborazzi writes only in record mode; this test reads its OWN frames rather than goldens.
        System.setProperty("roborazzi.test.record", "true")
    }

    /** The published shape: top-level `sections` with `data`, `particle_effect` beside them. */
    private fun paywall(withEffect: Boolean): PaywallConfig = PaywallConfigParser.parseSinglePaywall(
        "pw_celebration",
        buildMap {
            put("id", "pw_celebration")
            put("name", "Celebration")
            put("background", mapOf("type" to "color", "value" to "#FFFFFF"))
            put(
                "sections",
                listOf(
                    mapOf("id" to "sec_header", "type" to "header", "data" to mapOf("title" to "Go further")),
                    mapOf(
                        "id" to "sec_plans",
                        "type" to "plans",
                        "data" to mapOf(
                            "plan_display_style" to "vertical_stack",
                            // OPAQUE, both states: confetti composed UNDER the card could not show
                            // through it, so green inside the card's bounds means it is drawn on top.
                            "selected_bg_color" to "#333333",
                            "unselected_bg_color" to "#333333",
                            "plans" to listOf(
                                mapOf(
                                    "id" to "plan_annual",
                                    "product_id" to "app.premium.annual",
                                    "label" to planLabel,
                                    "price_display" to "$29.99",
                                    "is_default" to true,
                                ),
                            ),
                        ),
                    ),
                ),
            )
            put("cta", mapOf("text" to "Continue", "style" to mapOf("bg_color" to "#222222")))
            if (withEffect) {
                put(
                    "particle_effect",
                    mapOf(
                        "type" to "confetti",
                        "trigger" to "on_purchase",
                        "intensity" to "heavy",
                        "duration_ms" to 3000,
                        "colors" to listOf("#34C759"),
                    ),
                )
            }
        },
    ) ?: error("paywall did not parse")

    private fun render(withEffect: Boolean) {
        run = if (withEffect) "effect" else "control"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaywallScreen(config = paywall(withEffect), onPlanSelected = { _, _ -> }, onRestore = {}, onDismiss = {})
        }
        // Past the paywall's own entry animation, so a frame is the settled paywall.
        compose.mainClock.advanceTimeBy(1_000)
    }

    /**
     * Pixels tinted towards the authored green: in the whole frame, and inside [card].
     *
     * The confetti fades as it falls (alpha = 1 − t), so the composited colour is lighter than
     * `#34C759` — over white at t = 0.4 it is ≈ (133,195,124) — which is why this measures the TINT
     * (green above both other channels) rather than matching the hex. Nothing else on this paywall
     * has any: the page is white, the plan card and the CTA dark grey, the text grey or white, and
     * a grey has green == red == blue.
     */
    private fun green(card: android.graphics.Rect? = null): Pair<Int, Int> {
        /*
         * 🔴 ROBORAZZI, NOT `captureToImage()`. The paywall polls its signals in a `LaunchedEffect`
         * loop, so its tree is never idle and `captureToImage` times out waiting for a frame
         * (`ComposeTimeoutException … 2000 ms` — measured here first, and `SocialProofRenderTest`
         * met the same wall). Roborazzi draws the root itself; the frame is written and read back.
         */
        val path = "build/tmp/paywall-celebration/$run-frame-${frame++}.png"
        File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path)
        val f = File(path)
        check(f.exists()) { "no frame was written to $path — every count below would be about a missing file" }
        val bmp = android.graphics.BitmapFactory.decodeFile(path)
        var all = 0
        var inCard = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                if (g - r > 25 && g - b > 15) {
                    all += 1
                    if (card != null && card.contains(x, y)) inCard += 1
                }
            }
        }
        return all to inCard
    }

    /**
     * The plan card's bounds in root pixels. The label's MERGED node is the card (a plan row is one
     * clickable unit), so its bounds are the tile, not just the text.
     */
    private fun planCard(): android.graphics.Rect {
        val r = compose.onNodeWithText(planLabel, substring = true).fetchSemanticsNode().boundsInRoot
        return android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    /** Sum the green over the first half of the effect, a frame every 150 ms. */
    private fun greenWhileItFalls(card: android.graphics.Rect? = null): Pair<Int, Int> {
        var all = 0
        var inCard = 0
        repeat(10) {
            compose.mainClock.advanceTimeBy(150)
            val (a, c) = green(card)
            all += a
            inCard += c
        }
        return all to inCard
    }

    @Test
    fun `after a successful purchase the authored confetti is drawn, over the paywall`() {
        render(withEffect = true)
        val card = planCard()
        assertEquals("the paywall drew green BEFORE any purchase — the colour is not a clean marker", 0, green().first)

        PaywallActivity.signalPurchaseCelebrate() // what handlePostPurchaseSuccess calls, first thing

        val (onScreen, overTheCard) = greenWhileItFalls(card)
        println("[#1110] authored-green pixels while the confetti falls: whole frame=$onScreen, plan card $card=$overTheCard")
        assertTrue("no authored green after a successful purchase — the celebration did not draw (#1110)", onScreen > 0)
        assertTrue(
            "the confetti never appeared over the plan card — it is composed UNDER the paywall's own layers (#1110)",
            overTheCard > 0,
        )
    }

    /**
     * THE CONTROL: the same paywall with `particle_effect` removed, the same success signal, the same
     * frames. It must read zero, or "green appeared" above says something about this harness
     * (a highlight, an anti-aliased edge) rather than about the celebration.
     */
    @Test
    fun `THE CONTROL — with the effect cleared the same success draws no green`() {
        render(withEffect = false)
        PaywallActivity.signalPurchaseCelebrate()
        val seen = greenWhileItFalls().first
        println("[#1110] control (no particle_effect): authored-green pixels=$seen")
        assertEquals("green appeared with no particle_effect authored — the marker is not clean", 0, seen)
    }
}
