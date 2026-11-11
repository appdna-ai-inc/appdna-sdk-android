package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RENDERED, mid-scroll: the date wheel's rows stay inside the wheel and fade out at its top and bottom edge, the way the
 * system wheel's rows do, instead of sliding past the edge as a hard-cut line (iOS `DateWheelClipRenderTests`).
 *
 * The year column is moved a few dp at a time with no fling, so it is held between two rows the way a drag holds it, and
 * every position is read. THE CONTROLS: the same reader sees the rows inside the wheel, and sees full ink a row and a half
 * in from the edge, so a pass is not a harness that sees no ink at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DateWheelClipRenderTest {
    @get:Rule
    val compose = createComposeRule()

    // Roborazzi, not `captureToImage()`: the latter waits on a redraw this tree never reports and times out.
    @org.junit.Before
    fun enableCapture() { System.setProperty("roborazzi.test.record", "true") }

    private var frame = 0

    private fun show() {
        val block = ContentBlock(id = "wheel", type = "date_wheel_picker", field_id = "born", picker_mode = "date",
            default_date_value = "2010-06-15", min_date = "1950-01-01", max_date = "2025-12-31", highlight_color = "#00FF00",
            wheel_height = 200.0, field_config = mapOf("wheel_text_color" to "#0000FF"))
        compose.setContent {
            // White space above and below the wheel, so a row drawn outside it would land on something readable.
            Box(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 80.dp)) {
                DateWheelPickerBlock(block, mutableMapOf())
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** The strongest blue-over-red excess (the authored row ink) on each pixel line, inside the year column. */
    private fun ink(): Map<Int, Int> {
        val column = compose.onNodeWithTag("date_wheel.year").fetchSemanticsNode().boundsInRoot
        val path = "build/tmp/date-wheel-clip/frame-${frame++}.png"
        java.io.File(path).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: error("no frame at $path")
        val out = HashMap<Int, Int>()
        for (y in 0 until bmp.height) {
            var best = 0
            for (x in (column.left.toInt() + 4) until (column.right.toInt() - 4).coerceAtMost(bmp.width)) {
                val c = bmp.getPixel(x, y)
                // Weighted by the pixel's alpha: the captured frame keeps it, so a faded row is a blue pixel of low alpha.
                best = maxOf(best, android.graphics.Color.alpha(c) * (android.graphics.Color.blue(c) - android.graphics.Color.red(c)) / 255)
            }
            out[y] = best
        }
        return out
    }

    // The year column fills the wheel's height, so its bounds are the wheel's top and bottom.
    private fun wheel() = compose.onNodeWithTag("date_wheel.year").fetchSemanticsNode().boundsInRoot

    /** Moves the year column by `dp` with no fling (a held drag), then lets the frame settle. */
    private fun nudge(dp: Float) {
        val px = dp * 2f // xhdpi
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("date_wheel.year")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        compose.waitForIdle()
    }

    @Test fun rowsMidScrollAreDrawnOnlyInsideTheWheel() {
        show()
        val w = wheel()
        repeat(10) { step ->
            nudge(if (step < 5) 7f else -11f)
            val lines = ink()
            val outside = lines.filter { (it.key < w.top - 1 || it.key > w.bottom + 1) && it.value > 60 }.keys.sorted()
            val inside = lines.count { it.key >= w.top && it.key <= w.bottom && it.value > 60 }
            assertTrue("harness control: the reader sees the rows inside the wheel (step $step)", inside > 20)
            assertTrue("row ink outside the wheel ${w.top}..${w.bottom} at step $step: lines ${outside.take(12)}", outside.isEmpty())
        }
    }

    @Test fun aRowAtTheEdgeFadesOutInsteadOfBeingCutOff() {
        show()
        val w = wheel()
        var edge = 0
        var inner = 0
        // Every few dp across more than a row, so on some frame a row straddles each edge.
        repeat(12) {
            nudge(4f)
            val lines = ink()
            val top = w.top.toInt()
            val bottom = w.bottom.toInt()
            edge = maxOf(edge, (top until top + 6).maxOf { lines[it] ?: 0 }, (bottom - 6 until bottom).maxOf { lines[it] ?: 0 })
            // A row and a half in from the top edge (40dp rows at xhdpi): past the fade, drawn in full ink.
            inner = maxOf(inner, (top + 100 until top + 150).maxOf { lines[it] ?: 0 })
        }
        assertTrue("harness control: a row a row and a half in from the edge is drawn in full ink ($inner)", inner > 100)
        assertTrue("a row straddling the wheel's edge is drawn at full ink ($edge), cut off rather than faded", edge < 40)
    }
}
