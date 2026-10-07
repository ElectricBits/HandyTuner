package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Autocalibrate: an EVGA-style duty→RPM sweep is reduced to (a) the fan's real min-spin % — the lowest
 * duty that actually moves air on THIS unit — and (b) a recommended temp→% curve anchored to that
 * min-spin. Pure; the live sweep (writing duty, reading the tach) is verified on-device.
 */
class FanCalibrationTest {

    /** A clean sweep: fan starts moving around 30%, RPM rises with duty. */
    private fun risingSweep() = listOf(
        FanCalibrationSample(20, 0),
        FanCalibrationSample(30, 1200),
        FanCalibrationSample(40, 2600),
        FanCalibrationSample(60, 4200),
        FanCalibrationSample(80, 5400),
        FanCalibrationSample(100, 6000),
    )

    @Test fun `min-spin is the lowest duty where the fan actually starts moving air`() {
        val cal = FanCalibration.fromSweep(risingSweep())
        // 20% reads 0 RPM (stalled); 30% is the first duty with a real RPM rise → that's min-spin.
        assertEquals(30, cal.minSpinPercent)
    }

    @Test fun `max rpm is the top of the observed sweep`() {
        assertEquals(6000, FanCalibration.fromSweep(risingSweep()).maxRpm)
    }

    @Test fun `min-spin never drops below the vendor-safe floor`() {
        // A fan that already spins at the lowest swept duty must still not calibrate below MIN_PERCENT.
        val cal = FanCalibration.fromSweep(
            listOf(FanCalibrationSample(5, 1000), FanCalibrationSample(100, 6000)),
        )
        assertTrue("min-spin ${cal.minSpinPercent} < floor", cal.minSpinPercent >= FanCurve.MIN_PERCENT)
    }

    @Test fun `recommended curve runs from min-spin when cool to 100 percent at the thermal trip`() {
        val curve = FanCalibration.fromSweep(risingSweep()).recommendedCurve
        val pts = curve.points.sortedBy { it.tempC }
        assertEquals("cool anchor = min-spin", 30, pts.first().percent)
        assertEquals("hot anchor temp = thermal trip", FanCurve.THERMAL_OVERRIDE_C, pts.last().tempC)
        assertEquals("hot anchor = full", 100, pts.last().percent)
    }

    @Test fun `recommended curve is monotonic non-decreasing`() {
        val pts = FanCalibration.fromSweep(risingSweep()).recommendedCurve.points.sortedBy { it.tempC }
        for (i in 1 until pts.size) {
            assertTrue("percent dropped at ${pts[i].tempC}C", pts[i].percent >= pts[i - 1].percent)
        }
    }

    @Test fun `an unreadable tach (all-zero rpm) falls back to the safe default curve`() {
        val cal = FanCalibration.fromSweep(
            listOf(FanCalibrationSample(20, 0), FanCalibrationSample(100, 0)),
        )
        assertEquals(FanCurve.DEFAULT, cal.recommendedCurve)
        assertEquals(FanCurve.MIN_PERCENT, cal.minSpinPercent)
        assertEquals(0, cal.maxRpm)
    }

    @Test fun `no samples falls back to the safe default curve`() {
        val cal = FanCalibration.fromSweep(emptyList())
        assertEquals(FanCurve.DEFAULT, cal.recommendedCurve)
        assertEquals(FanCurve.MIN_PERCENT, cal.minSpinPercent)
    }

    // --- adaptive: each fan's own RPM-vs-duty curve, anchored to its measured idle temp ---

    @Test fun `comfort percent is the duty at a comfortable working RPM, not full blast`() {
        // maxRpm 10000; comfort target = 60% of that = 6000 RPM, first reached at 40% duty.
        val cal = FanCalibration.fromSweep(
            listOf(
                FanCalibrationSample(20, 0), FanCalibrationSample(40, 6000),
                FanCalibrationSample(60, 9000), FanCalibrationSample(100, 10000),
            ),
        )
        assertEquals(40, cal.comfortPercent)
    }

    @Test fun `the curve stays at or below the comfort level until it gets hot`() {
        // Below the hot knee the fan should be quiet (≤ comfort); it only ramps toward full near the trip.
        val cal = FanCalibration.fromSweep(risingSweep(), idleTempC = 45)
        val belowHot = cal.recommendedCurve.percentFor(70) // 70°C is still "normal", not hot
        assertTrue("curve at 70C ($belowHot) should not exceed comfort (${cal.comfortPercent})",
            belowHot <= cal.comfortPercent)
    }

    @Test fun `the curve is anchored to the measured idle temperature`() {
        val cool = FanCalibration.fromSweep(risingSweep(), idleTempC = 48).recommendedCurve
            .points.minByOrNull { it.tempC }!!
        assertEquals(48, cool.tempC) // the quiet/min-spin anchor sits at the device's real idle temp
    }

    @Test fun `two different fans produce two different curves (it actually adapts)`() {
        val quietFan = FanCalibration.fromSweep(
            listOf(FanCalibrationSample(20, 0), FanCalibrationSample(40, 4500), FanCalibrationSample(100, 5000)),
        )
        val rampyFan = FanCalibration.fromSweep(
            listOf(FanCalibrationSample(20, 0), FanCalibrationSample(60, 1500), FanCalibrationSample(100, 6000)),
        )
        assertNotEquals(quietFan.recommendedCurve, rampyFan.recommendedCurve)
    }
}
