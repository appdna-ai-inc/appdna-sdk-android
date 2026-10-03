package ai.appdna.sdk

import ai.appdna.sdk.onboarding.ContentBlockRendererView
import ai.appdna.sdk.onboarding.OnboardingConfigParser
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Measurement wheel_picker visual snapshots (ruler / gauge / dial / wheel),
 * parity with the iOS VisualSnapshotTests testMeasurement_* goldens. Robolectric renders
 * the REAL Compose UI on the JVM; captureRoboImage writes the pixels to a bridge-readable
 * path. Record: ./gradlew recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MeasurementWheelSnapshotTest {

    private fun wheelStep(style: String, type: String, def: Double, unitDefault: String, units: List<Map<String, Any>>): Map<String, Any> =
        mapOf(
            "type" to "custom", "name" to "t", "analytics_name" to "t", "skip_allowed" to false,
            "config" to mapOf<String, Any>(
                "content_blocks" to listOf(
                    mapOf<String, Any>(
                        "id" to "mw",
                        "type" to "wheel_picker",
                        "field_id" to "measure",
                        "highlight_color" to "#6366F1",
                        "field_config" to mapOf<String, Any>(
                            "measurement_type" to type,
                            "measurement_style" to style,
                            "measurement_default" to def,
                            "unit_default" to unitDefault,
                            "units" to units,
                        ),
                    ),
                ),
            ),
        )

    private val weightUnits = listOf(
        mapOf<String, Any>("id" to "kg", "label" to "kg", "min" to 30.0, "max" to 200.0, "step" to 0.5, "decimals" to 1, "factor" to 1.0, "offset" to 0.0),
        mapOf<String, Any>("id" to "lbs", "label" to "lbs", "min" to 66.0, "max" to 441.0, "step" to 1.0, "decimals" to 0, "factor" to 2.20462, "offset" to 0.0),
    )
    private val tempUnits = listOf(
        mapOf<String, Any>("id" to "c", "label" to "°C", "min" to 35.0, "max" to 42.0, "step" to 0.1, "decimals" to 1, "factor" to 1.0, "offset" to 0.0),
        mapOf<String, Any>("id" to "f", "label" to "°F", "min" to 95.0, "max" to 108.0, "step" to 0.1, "decimals" to 1, "factor" to 1.8, "offset" to 32.0),
    )
    private val heightUnits = listOf(
        mapOf<String, Any>("id" to "cm", "label" to "cm", "min" to 100.0, "max" to 220.0, "step" to 1.0, "decimals" to 0, "factor" to 1.0, "offset" to 0.0),
        mapOf<String, Any>("id" to "in", "label" to "in", "min" to 39.0, "max" to 87.0, "step" to 1.0, "decimals" to 0, "factor" to 0.393701, "offset" to 0.0),
    )

    private fun capture(name: String, step: Map<String, Any>) {
        val blocks = OnboardingConfigParser.parseStepForTest(step)?.config?.content_blocks ?: emptyList()
        captureRoboImage("src/test/snapshots/$name.png") {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().background(Color(0xFF0F1117)).padding(16.dp)) {
                    ContentBlockRendererView(blocks = blocks, onAction = {}, toggleValues = mutableMapOf(), inputValues = mutableMapOf())
                }
            }
        }
    }

    @Test fun measurement_ruler() = capture("measurement_ruler", wheelStep("ruler", "weight", 70.0, "kg", weightUnits))
    @Test fun measurement_gauge() = capture("measurement_gauge", wheelStep("gauge", "temperature", 37.0, "c", tempUnits))
    @Test fun measurement_dial() = capture("measurement_dial", wheelStep("dial", "height", 170.0, "cm", heightUnits))
    @Test fun measurement_wheel() = capture("measurement_wheel", wheelStep("wheel", "weight", 70.0, "kg", weightUnits))
}
