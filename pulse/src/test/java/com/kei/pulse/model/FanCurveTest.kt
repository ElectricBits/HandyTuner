package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Custom fan curve: smooth (monotone-cubic spline) interpolation, the 20% safety
 * floor, the 90C hard thermal override, duty conversion, response smoothing, and the persistence codec. The
 * live fan (writing gpio5_pwm2/duty on the Odin) is verified on-device; the math + codec are pure.
 */
class FanCurveTest {
    private val curve = FanCurve(
        listOf(
            FanCurvePoint(30, 40), FanCurvePoint(50, 50),
            FanCurvePoint(80, 80), FanCurvePoint(90, 100),
        ),
    )

    @Test fun `at a point returns that point's percent`() {
        assertEquals(50, curve.percentFor(50))
        assertEquals(80, curve.percentFor(80))
    }

    @Test fun `interpolates with a smooth spline, not straight lines`() {
        // Monotone-cubic spline through (30,40)(50,50)(80,80)(90,100): at 65 the curved line reads 62,
        // not the straight-segment 65 — that's the smoothing the user wanted.
        assertEquals(62, curve.percentFor(65))
    }

    @Test fun `the spline stays within the bracketing knees and rises monotonically`() {
        // For a non-decreasing curve the smooth interpolation must not dip or overshoot between knees.
        var prev = curve.percentFor(FanGraphGeometry.TEMP_MIN_C)
        for (t in FanGraphGeometry.TEMP_MIN_C..FanGraphGeometry.TEMP_MAX_C) {
            val v = curve.percentFor(t)
            assertTrue("dipped at $t ($v < $prev)", v >= prev)
            assertTrue("out of range at $t", v in FanCurve.MIN_PERCENT..100)
            prev = v
        }
    }

    @Test fun `below the first point holds the floor percent`() {
        assertEquals(40, curve.percentFor(20))
        assertEquals(40, curve.percentFor(0))
    }

    @Test fun `above the last point holds the top percent`() {
        assertEquals(100, curve.percentFor(95))
    }

    @Test fun `clamps a too-low curve point up to MIN_PERCENT`() {
        val low = FanCurve(listOf(FanCurvePoint(30, 5)))
        assertEquals(FanCurve.MIN_PERCENT, low.percentFor(30))
    }

    @Test fun `empty curve falls back to MIN_PERCENT`() {
        assertEquals(FanCurve.MIN_PERCENT, FanCurve(emptyList()).percentFor(60))
    }

    @Test fun `effectivePercent forces 100 at or above the thermal override`() {
        assertEquals(100, curve.effectivePercent(FanCurve.THERMAL_OVERRIDE_C))
        assertEquals(100, curve.effectivePercent(95))
        assertEquals(50, curve.effectivePercent(50)) // below override: follows the curve
    }

    @Test fun `unsorted points still interpolate correctly`() {
        val unsorted = FanCurve(listOf(FanCurvePoint(80, 80), FanCurvePoint(30, 40)))
        assertEquals(40, unsorted.percentFor(30))
        assertEquals(60, unsorted.percentFor(55)) // midway 30..80, 40..80
    }

    // --- duty conversion + smoothing ---

    @Test fun `percentToDuty maps percent across the PWM period`() {
        assertEquals(10000, FanCurve.percentToDuty(20, 50000)) // 20% floor
        assertEquals(25000, FanCurve.percentToDuty(50, 50000))
        assertEquals(50000, FanCurve.percentToDuty(100, 50000)) // full
    }

    @Test fun `easePercent steps toward the target by at most stepPercent`() {
        assertEquals(50, FanCurve.easePercent(40, 80, 10)) // ramp up one step of 10
        assertEquals(30, FanCurve.easePercent(40, 20, 10)) // ramp down one step of 10
        assertEquals(80, FanCurve.easePercent(75, 80, 10)) // within a step: snap to target
        assertEquals(60, FanCurve.easePercent(60, 60, 10)) // already there
    }

    // --- codec ---

    @Test fun `serialize then parse round-trips`() {
        val c = FanCurve(listOf(FanCurvePoint(30, 40), FanCurvePoint(90, 100)))
        assertEquals(c.points, FanCurve.parse(c.serialize()).points)
    }

    @Test fun `parse skips malformed entries and keeps valid ones`() {
        assertEquals(listOf(FanCurvePoint(30, 40)), FanCurve.parse("30:40,bad,:,90:x").points)
    }

    @Test fun `parse of blank or null falls back to DEFAULT`() {
        assertEquals(FanCurve.DEFAULT.points, FanCurve.parse("").points)
        assertEquals(FanCurve.DEFAULT.points, FanCurve.parse(null).points)
    }

    // --- Cooler/Quieter bias ---

    @Test fun `a cooler bias lifts every point, a quieter bias drops it`() {
        val c = FanCurve(listOf(FanCurvePoint(30, 40), FanCurvePoint(80, 80)))
        assertEquals(listOf(50, 90), c.shiftedBy(10).points.map { it.percent })
        assertEquals(listOf(30, 70), c.shiftedBy(-10).points.map { it.percent })
    }

    @Test fun `bias never pushes a point below the floor or above full`() {
        val c = FanCurve(listOf(FanCurvePoint(30, 25), FanCurvePoint(80, 95)))
        assertEquals("clamped to floor", FanCurve.MIN_PERCENT, c.shiftedBy(-30).points.first().percent)
        assertEquals("clamped to full", 100, c.shiftedBy(30).points.last().percent)
    }

    @Test fun `a zero bias returns the curve unchanged`() {
        val c = FanCurve(listOf(FanCurvePoint(30, 40)))
        assertEquals(c, c.shiftedBy(0))
    }
}
