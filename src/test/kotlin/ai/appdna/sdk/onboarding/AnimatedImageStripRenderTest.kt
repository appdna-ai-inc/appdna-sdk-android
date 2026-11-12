package ai.appdna.sdk.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The animated image MOVES: frames captured from the real [AnimatedImageStrip] on the Compose frame
 * clock, half a second apart, differ — by exactly speed × time, in the authored direction — and the
 * loop is seamless (one tile-period later the frame is the same again).
 *
 * THE CONTROL: with the clock not advanced the frame is unchanged, so "the frames differ" cannot pass
 * on a noisy harness; and a strip with no image draws nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-mdpi")
class AnimatedImageStripRenderTest {
    @get:Rule val compose = createComposeRule()

    private val red = 0xFFFF0000.toInt()

    /** 400×100 px: a 40px red bar at the leading edge, the rest blue. In a 50dp-high strip → 200dp tile. */
    private fun stripe(vertical: Boolean = false): BitmapPainter {
        val w = if (vertical) 100 else 400
        val h = if (vertical) 400 else 100
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) {
            val bar = if (vertical) y < 40 else x < 40
            bmp.setPixel(x, y, if (bar) red else 0xFF0000FF.toInt())
        }
        return BitmapPainter(bmp.asImageBitmap())
    }

    private var shot = 0
    private fun capture(): Bitmap {
        val path = "build/animated-image/${System.nanoTime()}-${shot++}.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame")
    }

    private fun show(direction: String, w: Int = 200, h: Int = 50, vertical: Boolean = false, painter: BitmapPainter? = stripe(vertical)) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(w.dp, h.dp).background(Color.White).clipToBounds()) {
                AnimatedImageStrip(painter = painter, direction = direction, speed = 40.0, modifier = Modifier.size(w.dp, h.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Leading x of the red bar on the middle row, in dp (mdpi: 1px = 1dp). */
    private fun redStartX(b: Bitmap): Int? = (0 until b.width).firstOrNull { b.getPixel(it, b.height / 2) == red }
    private fun redStartY(b: Bitmap): Int? = (0 until b.height).firstOrNull { b.getPixel(b.width / 2, it) == red }
    private fun same(a: Bitmap, b: Bitmap) = a.sameAs(b)

    @Test
    fun leftMovesBySpeedTimesTime() {
        show("left")
        compose.mainClock.advanceTimeBy(1000)
        val a = capture()
        val controlA = capture()
        compose.mainClock.advanceTimeBy(500)
        val b = capture()
        assertTrue("control: no time passed, same frame", same(a, controlA))
        assertFalse("the strip did not move", same(a, b))
        // Left: offset −40 at 1 s puts the second copy's bar at 160; 0.5 s more at 40dp/s is 20 further left.
        val ax = redStartX(a)!!
        val bx = redStartX(b)!!
        assertEquals(20.0, (ax - bx).toDouble(), 2.0)
        assertTrue("moved LEFT", bx < ax)
    }

    @Test
    fun rightMovesTheOtherWay() {
        show("right")
        compose.mainClock.advanceTimeBy(1000)
        val ax = redStartX(capture())!!
        compose.mainClock.advanceTimeBy(500)
        val bx = redStartX(capture())!!
        assertEquals(20.0, (bx - ax).toDouble(), 2.0)
    }

    @Test
    fun theLoopIsSeamless() {
        show("left")
        compose.mainClock.advanceTimeBy(1000)
        val a = capture()
        // One tile is 200dp; at 40dp/s a full period is 5 s.
        compose.mainClock.advanceTimeBy(5000)
        val b = capture()
        assertEquals(redStartX(a)!!.toDouble(), redStartX(b)!!.toDouble(), 2.0)
    }

    @Test
    fun upMovesVertically() {
        show("up", w = 50, h = 200, vertical = true)
        compose.mainClock.advanceTimeBy(1000)
        val ay = redStartY(capture())!!
        compose.mainClock.advanceTimeBy(500)
        val by = redStartY(capture())!!
        assertEquals(20.0, (ay - by).toDouble(), 2.0)
    }

    @Test
    fun noImageDrawsNothing() {
        show("left", painter = null)
        compose.mainClock.advanceTimeBy(1000)
        val f = capture()
        assertEquals(null, redStartX(f))
    }
}
