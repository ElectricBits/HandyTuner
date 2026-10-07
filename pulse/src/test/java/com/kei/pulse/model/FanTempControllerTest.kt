package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closed-loop temperature-target fan control — the control-theory ("EVGA/EE") approach. A PI controller
 * drives the fan duty to hold the SoC at a target temperature: silent (min-spin) when cool, ramping exactly
 * as much as needed when hot, with anti-windup so it recovers instantly after saturation and a hard 100%
 * override at the thermal trip. Pure + unit-tested; the live driving (slew + duty write) is the service's job.
 */
class FanTempControllerTest {
    private fun controller() = FanTempController(minSpinPercent = 20).apply { kp = 4.0; ki = 0.15 }

    @Test fun `at the target temperature it idles at the quiet min-spin floor`() {
        assertEquals(20, controller().update(currentTempC = 78, targetTempC = 78, dtSec = 1.0))
    }

    @Test fun `above the target it runs the fan harder than the floor`() {
        assertTrue(controller().update(currentTempC = 85, targetTempC = 78, dtSec = 1.0) > 20)
    }

    @Test fun `the hotter it is over target, the faster the fan`() {
        val warm = controller().update(82, 78, 1.0)
        val hot = controller().update(88, 78, 1.0)
        assertTrue("hot ($hot) should exceed warm ($warm)", hot > warm)
    }

    @Test fun `below the target it settles to the quiet floor`() {
        assertEquals(20, controller().update(60, 78, 1.0))
    }

    @Test fun `a sustained overshoot ramps the fan up over time (integral action)`() {
        val c = controller()
        val first = c.update(82, 78, 1.0)
        var last = first
        repeat(10) { last = c.update(82, 78, 1.0) }
        assertTrue("integral should keep pushing it up ($last !> $first)", last > first)
    }

    @Test fun `the thermal trip forces full fan regardless of the loop`() {
        assertEquals(100, controller().update(FanCurve.THERMAL_OVERRIDE_C, 78, 1.0))
    }

    @Test fun `output never drops below the floor or exceeds full`() {
        assertTrue(controller().update(40, 78, 1.0) >= 20)
        assertTrue(controller().update(120, 78, 1.0) <= 100)
    }

    @Test fun `recovers immediately after saturation — no integral windup`() {
        val c = controller()
        repeat(30) { c.update(89, 60, 1.0) } // far over target (still < trip) → pinned at 100, saturated
        // Back at target: with anti-windup the integral never wound up, so it returns straight to the floor.
        assertEquals(20, c.update(60, 60, 1.0))
    }

    @Test fun `reset clears the integral state`() {
        val c = controller()
        repeat(5) { c.update(85, 78, 1.0) }
        c.reset()
        assertEquals(20, c.update(78, 78, 1.0)) // as if fresh
    }
}
