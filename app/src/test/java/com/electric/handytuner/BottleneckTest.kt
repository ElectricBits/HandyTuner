// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import com.electric.handytuner.Bottleneck.Reading
import com.electric.handytuner.Bottleneck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class BottleneckTest {
    private fun r(fps: Int? = 45, gpu: Int? = 50, core: Int? = 50, temp: Int? = 70, mem: Double? = 0.0) =
        Reading(fps, gpu, core, temp, mem)

    @Test fun order() {
        assertEquals(Verdict.MEMORY, Bottleneck.of(r(gpu = 99, temp = 95, mem = 25.0)))
        assertEquals(Verdict.HEAT, Bottleneck.of(r(gpu = 99, temp = 90)))
        assertEquals(Verdict.HOT_OK, Bottleneck.of(r(fps = 60, temp = 90)))
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
}
