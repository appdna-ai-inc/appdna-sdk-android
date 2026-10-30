package ai.appdna.sdk

import ai.appdna.sdk.paywalls.PaywallConfigParser
import ai.appdna.sdk.paywalls.PaywallSectionView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * The comparison table's column headers, RENDERED.
 *
 * ## Why a pixel capture and not only the parser test
 *
 * `PaywallTableColumnsTest` proves the parser keeps a string-shaped `columns` array. It cannot
 * prove the renderer then DRAWS those labels — and the defect was a headerless table on a real
 * ACTIVE paywall, which is a render claim. Robolectric draws the REAL `PaywallSectionView` on the
 * JVM and `captureRoboImage` writes the actual pixels, which is the instrument that caught the
 * invisible selected plan when reading the code had not.
 *
 * ## The three captures are a discriminating set
 *
 * `columns_strings` is the shape live paywalls carry (`["Free", "Premium"]`) and the one Android
 * dropped. `columns_objects` is what the generator writes. `no_columns` is the CONTROL: the same
 * table with no column keys at all must draw a header row with no labels, so a capture that looks
 * identical to the other two would mean the pixels are not reading the config.
 *
 * Record:  ./gradlew recordRoborazziDebug
 * Compare: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class PaywallComparisonTableSnapshotTest {

    /**
     * A light page on purpose: a header drawn in the page's readable ink is DARK here, so a
     * dark-pixel count in the header band separates "the labels drew" from "the row is empty".
     * On a dark page both would be light and the measurement would prove nothing.
     */
    private fun paywallMap(columnsKey: String?, columns: Any?): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "rows" to listOf(
                mapOf("feature" to "Offline mode", "values" to listOf("cross", "check")),
                mapOf("feature" to "Unlimited exports", "values" to listOf("cross", "check")),
            ),
        )
        if (columnsKey != null && columns != null) data[columnsKey] = columns
        return mapOf(
            "id" to "pw_comparison_table",
            "name" to "Comparison table",
            "layout" to mapOf(
                "type" to "stack",
                "background" to mapOf("type" to "color", "value" to "#FFFFFF"),
            ),
            "sections" to listOf(
                mapOf(
                    "id" to "sec_table",
                    "type" to "comparison_table",
                    "data" to data,
                ),
            ),
        )
    }

    private fun capture(name: String, columnsKey: String?, columns: Any?) {
        val map = paywallMap(columnsKey, columns)
        val config = PaywallConfigParser.parseSinglePaywall("pw_comparison_table", map)
            ?: error("paywall did not parse")
        val section = config.sections.first { it.type == "comparison_table" }
        captureRoboImage("src/test/snapshots/$name.png") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFFFFF))
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

    /** The LIVE shape — what the Console writes, and what Android used to drop entirely. */
    @Test
    fun columnsAsPlainStrings() =
        capture("paywall_comparison_table_columns_strings", "columns", listOf("Free", "Premium"))

    /** The canonical shape, with a highlight the string form cannot express. */
    @Test
    fun tableColumnsAsObjects() = capture(
        "paywall_comparison_table_columns_objects",
        "table_columns",
        listOf(
            mapOf("label" to "Free"),
            mapOf("label" to "Premium", "highlighted" to true),
        ),
    )

    /** The control: no column keys at all must NOT draw labels. */
    @Test
    fun noColumnsDrawsNoLabels() = capture("paywall_comparison_table_no_columns", null, null)
}
