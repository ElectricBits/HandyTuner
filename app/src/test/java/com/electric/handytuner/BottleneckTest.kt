// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import com.electric.handytuner.Bottleneck.Reading
import com.electric.handytuner.Bottleneck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckTest {
    private fun r(fps: Int? = 45, gpu: Int? = 50, core: Int? = 50, temp: Int? = 70, mem: Double? = 0.0, hot: Boolean? = false,
                  held: Int? = null, auto: Boolean = false) =
        Reading(fps, gpu, core, temp, mem, hot, held, auto)

    @Test fun order() {
        assertEquals(Verdict.MEMORY, Bottleneck.of(r(gpu = 99, temp = 95, mem = 25.0, hot = true)))
        assertEquals(Verdict.HEAT, Bottleneck.of(r(gpu = 99, temp = 90, hot = true)))
        assertEquals(Verdict.HOT_OK, Bottleneck.of(r(fps = 60, temp = 90, hot = true, held = 60)))
        assertEquals(Verdict.GPU, Bottleneck.of(r(gpu = 95, core = 95)))
        assertEquals(Verdict.CPU, Bottleneck.of(r(core = 97)))
        assertEquals(Verdict.CAP, Bottleneck.of(r(fps = 59, held = 60)))
        assertEquals(Verdict.NONE, Bottleneck.of(r()))
        assertEquals(Verdict.NONE, Bottleneck.of(Reading(null, null, null, null, null)))
    }

    /** "Holding a cap" only when this game really has one: a game that runs at 60 on its own (on Max) is not capped. */
    @Test fun capOnlyWhenTheGameHasOne() {
        assertEquals(Verdict.STEADY, Bottleneck.of(r(fps = 60)))
        assertEquals(Verdict.STEADY, Bottleneck.of(r(fps = 58, core = 97)))   // a self-locked game isn't CPU-limited
        assertEquals("Steady at 60 fps", Verdict.STEADY.text(r(fps = 59)))
        assertEquals(Verdict.CAP, Bottleneck.of(r(fps = 41, held = 40)))
        assertEquals(Verdict.NONE, Bottleneck.of(r(fps = 55, held = 40)))
        assertEquals("Holding your 40 fps cap", Verdict.CAP.text(r(held = 40)))
        assertEquals("Auto is holding 30 fps", Verdict.CAP.text(r(held = 30, auto = true)))
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
