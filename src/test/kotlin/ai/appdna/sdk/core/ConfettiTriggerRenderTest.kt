package ai.appdna.sdk.core

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A PARTICLE EFFECT DRAWS WHATEVER ITS AUTHORED TRIGGER SAYS.
 *
 * Both overlays used to gate twice. The parent decided WHEN to show the overlay — the paywall
 * after a purchase, the survey on completion — and the overlay then re-checked the authored
 * trigger and refused anything that was not `on_appear`:
 *
 *     if (trigger && (effect.trigger == "on_appear" || isActive)) { … }
 *
 * So an effect authored `on_purchase` was armed by its parent and declined here, and three of the
 * four triggers the console offers had never drawn anything. Worse, `PaywallConfig.kt` DEFAULTS an
 * unauthored paywall effect to `on_purchase`, so the default could not fire either.
 *
 * 🔴 WHY A RENDER AND NOT A DEVICE WALK. The moment this effect exists for is a completed
 * purchase, which needs a store, and the iOS side could not even snapshot it: a static capture
 * reads a SwiftUI layer's model values, and the model is already at the animation's end — y past
 * the bottom of the screen and opacity 0. Compose has what that lacks: a clock the test owns. At
 * 300 ms into a 20-second effect the particles are a fifth of the way down the screen at full
 * alpha, and the authored colour is a flat `#00FF00` that nothing else on the frame can produce.
 *
 * 🔴 The NEGATIVE CONTROL is the unarmed case, and it is the assertion that makes the rest mean
 * anything: with `trigger = false` the parent has not armed the overlay and the frame must hold no
 * green at all. Without it, a harness that captured a black rectangle would "pass" every case.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ConfettiTriggerRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun effect(trigger: String) = ParticleEffect(
        type = "confetti",
        trigger = trigger,
        duration_ms = 20_000,
        intensity = "heavy",
        colors = listOf("#00FF00"),
    )

    /** How many pixels of the captured frame are the authored green. */
    private fun greenPixels(trigger: String, armed: Boolean): Int {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                ConfettiOverlay(effect = effect(trigger), trigger = armed)
            }
        }
        // Let the LaunchedEffect start, then move a fifth of a second into a 20-second animation.
        compose.mainClock.advanceTimeBy(16)
        compose.mainClock.advanceTimeBy(300)
        val bmp: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var green = 0
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (g > 140 && r < 110 && b < 110) green += 1
        }
        return green
    }

    @Test
    fun theUnarmedOverlayDrawsNothing() {
        assertEquals(
            "the parent did not arm the overlay and it drew particles anyway",
            0,
            greenPixels("on_appear", armed = false),
        )
    }

    @Test
    fun onAppearDraws() {
        assertTrue("on_appear drew no particles — the harness cannot see them", greenPixels("on_appear", armed = true) > 0)
    }

    @Test
    fun onPurchaseDraws() {
        assertTrue(
            "an effect authored on_purchase drew nothing — the overlay re-gated its trigger",
            greenPixels("on_purchase", armed = true) > 0,
        )
    }

    @Test
    fun onStepCompleteDraws() {
        assertTrue(
            "an effect authored on_step_complete drew nothing — the overlay re-gated its trigger",
            greenPixels("on_step_complete", armed = true) > 0,
        )
    }

    @Test
    fun onFlowCompleteDraws() {
        assertTrue(
            "an effect authored on_flow_complete drew nothing — the overlay re-gated its trigger",
            greenPixels("on_flow_complete", armed = true) > 0,
        )
    }
}
