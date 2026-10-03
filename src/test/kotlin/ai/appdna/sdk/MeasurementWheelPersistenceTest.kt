package ai.appdna.sdk

import ai.appdna.sdk.onboarding.MeasurementUnit
import ai.appdna.sdk.onboarding.measurementFromBase
import ai.appdna.sdk.onboarding.measurementRoundHalfAway
import ai.appdna.sdk.onboarding.measurementSnap
import ai.appdna.sdk.onboarding.measurementSnapshot
import ai.appdna.sdk.onboarding.measurementToBase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure persistence + delegate-payload contract for wheel-picker
 * measurement mode (Android leg). Mirrors iOS `MeasurementPersistenceTests.swift`
 * + the existing `InteractionResultTest`. Asserts the executed guarantee (not the
 * render): the base scalar is unit-stable across a toggle; the sibling keys are
 * self-consistent (`_unit` annotates the BASE, not the display unit).
 */
class MeasurementWheelPersistenceTest {

    // weight preset: kg (canonical base) ↔ lbs
    private val kg = MeasurementUnit("kg", "kg", 30.0, 200.0, 0.5, 1, 1.0, 0.0)
    private val lbs = MeasurementUnit("lbs", "lbs", 66.0, 441.0, 1.0, 0, 2.20462, 0.0)
    // temperature preset: °C (base) ↔ °F (offset conversion)
    private val c = MeasurementUnit("c", "°C", 35.0, 42.0, 0.1, 1, 1.0, 0.0)
    private val f = MeasurementUnit("f", "°F", 95.0, 108.0, 0.1, 1, 1.8, 32.0)

    // MARK: persistence contract

    @Test
    fun pick154LbsPersistsBaseScalarAndSelfConsistentSiblings() {
        // User picks 154 lbs while the display unit is lbs → base = 154/2.20462 ≈ 69.858 kg.
        val base = measurementToBase(154.0, lbs)
        val snap = measurementSnapshot(fieldId = "weight", base = base, baseUnit = kg, displayUnit = lbs)

        assertEquals(70L, snap.inputValues["weight"])                  // snapped+clamped BASE scalar (kg)
        assertEquals("kg", snap.inputValues["weight_unit"])            // annotates the base scalar
        assertEquals("lbs", snap.inputValues["weight_display_unit"])   // user's chosen unit
        assertEquals(154L, snap.inputValues["weight_display_value"])   // value in the display unit

        assertEquals(70L, snap.payload["value"])
        assertEquals(154L, snap.payload["display_value"])
        assertEquals("lbs", snap.payload["unit"])
    }

    @Test
    fun toggleHoldsBaseConstantAndConvertsDisplay() {
        // Same base (~69.858 kg) but display unit is now kg → base scalar UNCHANGED,
        // display + _display_unit + payload reflect kg.
        val base = measurementToBase(154.0, lbs)
        val snap = measurementSnapshot(fieldId = "weight", base = base, baseUnit = kg, displayUnit = kg)

        assertEquals(70L, snap.inputValues["weight"])                  // unit-stable base scalar
        assertEquals("kg", snap.inputValues["weight_unit"])            // still the base unit
        assertEquals("kg", snap.inputValues["weight_display_unit"])
        assertEquals(70L, snap.inputValues["weight_display_value"])    // 69.858 kg → snap step 0.5 → 70
        assertEquals("kg", snap.payload["unit"])
    }

    @Test
    fun offsetConversionTemperature() {
        // 37.0 °C held as base; display in °F = 37*1.8+32 = 98.6. Base 37.0 whole → Long.
        val snap = measurementSnapshot(fieldId = "temp", base = 37.0, baseUnit = c, displayUnit = f)
        assertEquals(37L, snap.inputValues["temp"])
        assertEquals("c", snap.inputValues["temp_unit"])
        assertEquals("f", snap.inputValues["temp_display_unit"])
        assertEquals(98.6, snap.inputValues["temp_display_value"] as Double, 0.0001)
        assertEquals(98.6, snap.payload["display_value"] as Double, 0.0001)
        assertEquals("f", snap.payload["unit"])
    }

    // MARK: pinned snap algorithm

    @Test
    fun snapClampsToBaseRange() {
        // A lbs extreme can convert to a base slightly outside [kg.min,kg.max];
        // the clamp (final op) absorbs it → exactly the boundary.
        assertEquals(200.0, measurementSnap(250.0, kg), 0.0) // above max 200
        assertEquals(30.0, measurementSnap(10.0, kg), 0.0)   // below min 30
    }

    @Test
    fun snapHalfAwayFromZeroOnNegatives() {
        val u = MeasurementUnit("x", "x", -100.0, 100.0, 1.0, 0, 1.0, 0.0)
        assertEquals(3.0, measurementSnap(2.5, u), 0.0)    // half away → up
        assertEquals(-3.0, measurementSnap(-2.5, u), 0.0)  // half away → down (NOT -2 like half-to-even)
    }

    @Test
    fun roundHalfAwaySignZero() {
        assertEquals(0.0, measurementRoundHalfAway(0.0), 0.0)
        assertEquals(1.0, measurementRoundHalfAway(0.5), 0.0)
        assertEquals(-1.0, measurementRoundHalfAway(-0.5), 0.0)
    }

    @Test
    fun conversionRoundTripFromBase() {
        // 70 kg → lbs → back to kg is lossless in base domain.
        val lbsVal = measurementFromBase(70.0, lbs)
        assertEquals(154.32, lbsVal, 0.01)
        assertEquals(70.0, measurementToBase(lbsVal, lbs), 0.0001)
    }
}
