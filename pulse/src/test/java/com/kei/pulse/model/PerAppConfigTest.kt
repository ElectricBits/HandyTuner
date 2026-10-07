package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppConfigTest {

    @Test
    fun `Odin SoC honors the Game Mode cap and gets its target list`() {
        assertTrue(PerAppConfig.isGameModeCapSoc("CQ8725S"))
        // 90 + 40 omitted: the Odin panel is 60/120 only, so Android floors a 90 cap to 60 and a 40 to 30 —
        // a "90" would silently be 60. Offer only the rates the panel can actually pace.
        assertEquals(listOf(30, 60, 120), PerAppConfig.fpsTargetsFor("CQ8725S"))
    }

    @Test
    fun `8 Gen 2 and unknown SoCs use the refresh-rate target list`() {
        assertFalse(PerAppConfig.isGameModeCapSoc("QCS8550"))
        assertEquals(listOf(60, 90, 120), PerAppConfig.fpsTargetsFor("QCS8550"))
        assertEquals(listOf(60, 90, 120), PerAppConfig.fpsTargetsFor(null))
    }

    @Test
    fun `SoC matching tolerates case and whitespace`() {
        assertTrue(PerAppConfig.isGameModeCapSoc(" cq8725s "))
    }

    @Test
    fun `useGameModeCap only for Odin targets that evenly divide the panel max refresh`() {
        // The Game Mode cap only paces cleanly when the target divides 120; Android floors a non-divisor to
        // the nearest divisor (90 → 60), so 90 takes neither path and isn't offered on the Odin at all.
        assertTrue(PerAppConfig.useGameModeCap("CQ8725S", 30, 120))
        assertTrue(PerAppConfig.useGameModeCap("CQ8725S", 60, 120))
        assertTrue(PerAppConfig.useGameModeCap("CQ8725S", 120, 120))
        assertFalse("90 floors to 60 on a 120 Hz panel — not a clean cap", PerAppConfig.useGameModeCap("CQ8725S", 90, 120))
        // Thor/RP6 never use the Game Mode cap (firmware doesn't honor it) — they take the refresh path.
        assertFalse(PerAppConfig.useGameModeCap("QCS8550", 60, 120))
    }

    @Test
    fun `the Odin no longer offers the unachievable 90 target`() {
        assertFalse("90 can't be paced on the Odin's 60/120 panel", 90 in PerAppConfig.fpsTargetsFor("CQ8725S"))
    }

    @Test
    fun `snapFpsTarget maps stale or out-of-range values to a valid option`() {
        // Legacy 50 → nearest valid (60) on the Odin list [30,60,120].
        assertEquals(60, PerAppConfig.snapFpsTarget(50, PerAppConfig.FPS_TARGET_OPTIONS))
        // Legacy 40/45 → nearest valid (30) on the Odin list.
        assertEquals(30, PerAppConfig.snapFpsTarget(40, PerAppConfig.FPS_TARGET_OPTIONS))
        // A saved 90 (no longer offered on the Odin's 60/120 panel) snaps to the nearest clean rate (60).
        assertEquals(60, PerAppConfig.snapFpsTarget(90, PerAppConfig.FPS_TARGET_OPTIONS))
        // Legacy Max(0) → the highest concrete rate, not the lowest.
        assertEquals(120, PerAppConfig.snapFpsTarget(0, PerAppConfig.FPS_TARGET_OPTIONS))
        // An Odin value (40) carried onto an 8 Gen 2 → nearest valid panel rate (60).
        assertEquals(60, PerAppConfig.snapFpsTarget(40, PerAppConfig.FPS_TARGET_OPTIONS_REFRESH))
        // A valid value passes through unchanged.
        assertEquals(60, PerAppConfig.snapFpsTarget(60, PerAppConfig.FPS_TARGET_OPTIONS))
    }

    @Test
    fun `idle draw samples are frozen out of the per-app peak and average`() {
        // Settle on a real ~7 W gameplay draw (peak rise-limits up over a few samples).
        var c = PerAppConfig("g")
        repeat(30) { c = c.foldMeasuredDraw(7f, active = true) }
        assertEquals(7f, c.measuredPeakW, 0.01f)
        assertEquals(7f, c.measuredAvgW, 0.1f)
        // A long idle/paused stretch at ~0.5 W must NOT drag either figure — the bug that made a 7 W game
        // read as ≈13 h of runtime.
        repeat(200) { c = c.foldMeasuredDraw(0.5f, active = false) }
        assertEquals("idle avg frozen out", 7f, c.measuredAvgW, 0.1f)
        assertEquals("idle peak frozen out", 7f, c.measuredPeakW, 0.01f)
    }

    @Test
    fun `the average reflects active play even with idle interleaved`() {
        var c = PerAppConfig("g")
        // Alternate real play (7 W) with idle/paused (0.5 W); only the play samples count. The old naive EMA
        // averaged both toward ~3.75 W and doubled the runtime estimate.
        repeat(100) {
            c = c.foldMeasuredDraw(7f, active = true)
            c = c.foldMeasuredDraw(0.5f, active = false)
        }
        assertEquals("avg ignores interleaved idle", 7f, c.measuredAvgW, 0.2f)
    }

    @Test
    fun `a single current_now spike cannot bake in a false peak`() {
        var c = PerAppConfig("g")
        repeat(20) { c = c.foldMeasuredDraw(7f, active = true) } // settle peak at 7
        val before = c.measuredPeakW
        c = c.foldMeasuredDraw(20f, active = true) // one noisy current_now reading
        assertTrue("peak rose by at most the rise limit", c.measuredPeakW <= before + PerAppConfig.PEAK_RISE_W + 1e-3f)
        assertTrue("didn't jump to the spike", c.measuredPeakW < 9f)
    }
}
