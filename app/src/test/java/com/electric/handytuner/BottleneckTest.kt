// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import com.electric.handytuner.Bottleneck.Reading
import com.electric.handytuner.Bottleneck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckTest {
    private fun r(fps: Int? = 45, gpu: Int? = 50, core: Int? = 50, temp: Int? = 70, mem: Double? = 0.0, hot: Boolean? = false) =
        Reading(fps, gpu, core, temp, mem, hot)

    @Test fun order() {
        assertEquals(Verdict.MEMORY, Bottleneck.of(r(gpu = 99, temp = 95, mem = 25.0, hot = true)))
        assertEquals(Verdict.HEAT, Bottleneck.of(r(gpu = 99, temp = 90, hot = true)))
        assertEquals(Verdict.HOT_OK, Bottleneck.of(r(fps = 60, temp = 90, hot = true)))
        assertEquals(Verdict.GPU, Bottleneck.of(r(gpu = 95, core = 95)))
        assertEquals(Verdict.CPU, Bottleneck.of(r(core = 97)))
        assertEquals(Verdict.CAP, Bottleneck.of(r(fps = 59)))
        assertEquals(Verdict.NONE, Bottleneck.of(r()))
        assertEquals(Verdict.NONE, Bottleneck.of(Reading(null, null, null, null, null)))
    }

    /**
     * Every rate HandyTuner can ask Pulse to hold at must read as "at its cap". 40 was missed here
     * and read as "No bottleneck" (docs/BUGS.md #9), so the list is checked against the real sets
     * rather than restated by hand.
     */
    @Test fun everyCapAndTargetReadsAsHeld() {
        (PulsePart.CAPS - 0).forEach { cap ->
            listOf(cap, cap - 1, cap + 1).forEach { fps ->
                assertEquals("cap $cap at $fps fps", Verdict.CAP, Bottleneck.of(r(fps = fps)))
            }
        }
        PulsePart.AUTO_FPS.forEach { fps ->
            assertEquals("AutoTDP target $fps", Verdict.CAP, Bottleneck.of(r(fps = fps)))
        }
    }

    /** A rate nobody asks for is not a cap: 45 is not offered by this panel at all. */
    @Test fun anUnofferedRateIsNotACap() {
        assertEquals(Verdict.NONE, Bottleneck.of(r(fps = 45)))
        assertEquals(Verdict.NONE, Bottleneck.of(r(fps = 24)))
    }

    /** Hot is not throttling: only the kernel's cooling devices can say the chip is being slowed. */
    @Test fun heatOnlyWhenReallyThrottling() {
        assertEquals(Verdict.GPU, Bottleneck.of(r(gpu = 99, temp = 95, hot = false)))
        assertEquals(Verdict.NONE, Bottleneck.of(r(temp = 95, hot = null)))
        assertEquals(Verdict.HEAT, Bottleneck.of(r(temp = 60, hot = true)))
    }

    @Test fun anyEngagedCoolingDeviceIsThrottling() {
        assertEquals(false, Stats.throttled(listOf(0, 0, 0), 0))
        assertEquals(true, Stats.throttled(listOf(0, 3, 0), 0))
        assertEquals(true, Stats.throttled(emptyList(), 2))
        assertEquals(false, Stats.throttled(emptyList(), null))
        assertEquals(true, Stats.COOLING.matches("cpufreq-cpu7"))
        assertEquals(true, Stats.COOLING.matches("devfreq-3d00000.qcom,kgsl-3d0"))
        assertEquals(false, Stats.COOLING.matches("battery"))
    }
}
