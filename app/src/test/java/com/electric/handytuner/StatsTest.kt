// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smoothing that keeps a one-second spike from being reported as heat.
 *
 * Measured on the Odin 2 Portal while idle: the hottest of the four core zones sat at 39–42 °C and
 * never came near the 88 °C line, yet "Running hot" kept appearing. A single busy second was enough.
 */
class StatsTest {
    private val s = Stats()

    @Test fun startsAtTheFirstReading() {
        assertEquals(40.0, s.ema(null, 40), 0.01)
    }

    @Test fun oneSpikeDoesNotReachTheHeatLine() {
        var e: Double? = 40.0
        e = s.ema(e, 95)
        // A single 95 °C sample from a 40 °C baseline is 43 °C smoothed - nowhere near 88.
        assertTrue("spike smoothed to $e", e < 88.0)
    }

    @Test fun sustainedHeatStillGetsThrough() {
        var e: Double? = 40.0
        repeat(40) { e = s.ema(e, 95) }
        // 40 samples is 40 seconds of genuinely hot, which should be reported, not smoothed away.
        assertTrue("sustained heat smoothed to $e", e!! > 88.0)
    }

    @Test fun coolsBackDown() {
        var e: Double? = null
        repeat(40) { e = s.ema(e, 92) }
        repeat(40) { e = s.ema(e, 40) }
        assertTrue("cooled to $e", e!! < 50.0)
    }
}