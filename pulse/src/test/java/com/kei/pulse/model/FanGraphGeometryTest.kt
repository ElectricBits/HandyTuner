package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry/edit math behind the EVGA-style curve editor. The Compose canvas only does drawing +
 * gestures (verified on-device — can't screenshot PULSE); all the temp%↔coordinate mapping and the
 * "drag a knee, keep it ordered and clamped" logic lives here so it's unit-tested.
 */
class FanGraphGeometryTest {

    // --- coordinate mapping (x = temp left→right, y = percent with 100% at the TOP) ----------------

    @Test fun `the hottest, fullest point maps to the top-right corner`() {
        val (x, y) = FanGraphGeometry.toNorm(FanGraphGeometry.TEMP_MAX_C, 100)
        assertEquals(1f, x, 0.001f)
        assertEquals(0f, y, 0.001f) // 100% is at the top (y=0)
    }

    @Test fun `the coolest, zero point maps to the bottom-left corner`() {
        val (x, y) = FanGraphGeometry.toNorm(FanGraphGeometry.TEMP_MIN_C, 0)
        assertEquals(0f, x, 0.001f)
        assertEquals(1f, y, 0.001f) // 0% is at the bottom (y=1)
    }

    @Test fun `mapping a point to coordinates and back returns the same point`() {
        val p = FanCurvePoint(tempC = 60, percent = 70)
        val (x, y) = FanGraphGeometry.toNorm(p.tempC, p.percent)
        assertEquals(p, FanGraphGeometry.fromNorm(x, y))
    }

    @Test fun `coordinates outside the graph clamp to the axis range and the fan floor`() {
        val below = FanGraphGeometry.fromNorm(-0.5f, 2f) // off the left, below the bottom
        assertEquals(FanGraphGeometry.TEMP_MIN_C, below.tempC)
        assertEquals(FanCurve.MIN_PERCENT, below.percent) // never below the vendor-safe floor
        val above = FanGraphGeometry.fromNorm(1.5f, -0.5f) // off the right, above the top
        assertEquals(FanGraphGeometry.TEMP_MAX_C, above.tempC)
        assertEquals(100, above.percent)
    }

    // --- dragging a knee --------------------------------------------------------------------------

    private val curve = listOf(
        FanCurvePoint(30, 40), FanCurvePoint(50, 50), FanCurvePoint(80, 80), FanCurvePoint(90, 100),
    )

    @Test fun `moving a knee updates only that point`() {
        val out = FanCurveEditing.movePoint(curve, index = 1, newTempC = 55, newPercent = 60)
        assertEquals(FanCurvePoint(55, 60), out[1])
        assertEquals(curve[0], out[0])
        assertEquals(curve[2], out[2])
    }

    @Test fun `a knee cannot be dragged past its neighbors' temperatures`() {
        // index 1 sits between 30 and 80; dragging its temp to 200 must stop below the next knee (80).
        val out = FanCurveEditing.movePoint(curve, index = 1, newTempC = 200, newPercent = 60)
        assertTrue("temp ${out[1].tempC} not < next knee", out[1].tempC < curve[2].tempC)
        assertTrue("temp ${out[1].tempC} not > prev knee", out[1].tempC > curve[0].tempC)
    }

    @Test fun `a dragged knee percent is clamped to the safe range`() {
        assertEquals(100, FanCurveEditing.movePoint(curve, 3, 90, 999)[3].percent)
        assertEquals(FanCurve.MIN_PERCENT, FanCurveEditing.movePoint(curve, 0, 30, -5)[0].percent)
    }

    @Test fun `the result stays sorted by temperature`() {
        val out = FanCurveEditing.movePoint(curve, index = 2, newTempC = 60, newPercent = 70)
        val temps = out.map { it.tempC }
        assertEquals(temps.sorted(), temps)
    }

    // --- dragging a knee while a Cooler/Quieter bias is applied ---
    // The graph shows the EFFECTIVE curve (base + bias); a drag is in that effective space and must update
    // the BASE so that base + bias lands where the finger is.

    @Test fun `dragging a knee with a cooler bias updates the base so base plus bias matches the finger`() {
        // bias +10: the user drops index 1 to an effective 70% → base must become 60 (60 + 10 = 70).
        val out = FanCurveEditing.movePointBiased(curve, index = 1, newTempC = 55, newEffectivePercent = 70, bias = 10)
        assertEquals(60, out[1].percent)
    }

    @Test fun `dragging a knee with a quieter bias updates the base accordingly`() {
        // bias −10: dropping index 0 to an effective 30% → base must become 40 (40 − 10 = 30).
        val out = FanCurveEditing.movePointBiased(curve, index = 0, newTempC = 30, newEffectivePercent = 30, bias = -10)
        assertEquals(40, out[0].percent)
    }

    // --- adding / removing knees (tap to add, drag off to remove) ---

    @Test fun `adding a point inserts it in temperature order`() {
        val out = FanCurveEditing.addPoint(curve, tempC = 65, percent = 70)
        assertEquals(listOf(30, 50, 65, 80, 90), out.map { it.tempC })
        assertEquals(70, out.first { it.tempC == 65 }.percent)
    }

    @Test fun `adding a point clamps it to the axis and the safe floor`() {
        val out = FanCurveEditing.addPoint(curve, tempC = 200, percent = 5)
        val added = out.maxByOrNull { it.tempC }!!
        assertTrue(added.tempC <= FanGraphGeometry.TEMP_MAX_C)
        assertEquals(FanCurve.MIN_PERCENT, added.percent)
    }

    @Test fun `adding a point on top of an existing knee is rejected`() {
        val out = FanCurveEditing.addPoint(curve, tempC = 50, percent = 90) // 50 already exists
        assertEquals(curve.size, out.size)
    }

    @Test fun `removing a knee drops it`() {
        val out = FanCurveEditing.removePoint(curve, index = 1)
        assertEquals(listOf(30, 80, 90), out.map { it.tempC })
    }

    @Test fun `removing is refused when only two knees remain`() {
        val two = listOf(FanCurvePoint(30, 40), FanCurvePoint(90, 100))
        assertEquals(two, FanCurveEditing.removePoint(two, index = 0))
    }
}
