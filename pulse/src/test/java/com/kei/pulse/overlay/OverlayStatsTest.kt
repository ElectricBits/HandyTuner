package com.kei.pulse.overlay

import com.kei.pulse.model.OverlayElement
import com.kei.pulse.model.OverlayPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the overlay's battery "LEFT" estimate and the preset → element-set bundles. The
 * on-device render is verified by eye (no screencap), but the math and the quick-fill defaults are pure.
 */
class OverlayStatsTest {

    // ~8000 mAh @ 3.85 V ≈ 30.8 Wh; use a round 30 Wh so the arithmetic is obvious.
    private val cap = 30f

    @Test
    fun `full battery at 10W is three hours`() {
        // 30 Wh / 10 W = 3 h = 180 min.
        assertEquals(180, batteryMinutesLeft(cap, batteryPercent = 100, smoothedWatts = 10f, discharging = true))
    }

    @Test
    fun `minutes-left EMA seeds on the first reading then trails`() {
        // First trusted reading seeds the EMA exactly; later readings only nudge it (slow, glanceable).
        val seed = smoothMinutes(prevEma = null, rawMinutes = 180, alpha = 0.05f)
        assertEquals(180f, seed!!, 0.01f)
        val next = smoothMinutes(prevEma = seed, rawMinutes = 120, alpha = 0.05f) // a noisy dip
        assertEquals("a single dip barely moves the slow EMA", 177f, next!!, 0.5f) // 180*.95 + 120*.05
    }

    @Test
    fun `a null raw drops the smoothing state`() {
        // Charging / paused (untrusted) clears the EMA so it re-seeds cleanly when discharge resumes.
        assertNull(smoothMinutes(prevEma = 180f, rawMinutes = null, alpha = 0.05f))
    }

    @Test
    fun `display quantizes to a stable step so the number doesn't flicker`() {
        // 1-minute jitter on the EMA must not change the shown value: round to the nearest step.
        assertEquals(180, displayMinutes(181.4f, stepMin = 5))
        assertEquals(180, displayMinutes(178.6f, stepMin = 5))
        assertEquals(185, displayMinutes(183f, stepMin = 5))
        assertNull(displayMinutes(null, stepMin = 5))
    }

    @Test
    fun `half battery scales the estimate`() {
        // 30 Wh × 0.5 = 15 Wh / 10 W = 1.5 h = 90 min.
        assertEquals(90, batteryMinutesLeft(cap, batteryPercent = 50, smoothedWatts = 10f, discharging = true))
    }

    @Test
    fun `percent above 100 is clamped`() {
        assertEquals(180, batteryMinutesLeft(cap, batteryPercent = 120, smoothedWatts = 10f, discharging = true))
    }

    @Test
    fun `charging returns null`() {
        assertNull(batteryMinutesLeft(cap, batteryPercent = 50, smoothedWatts = 10f, discharging = false))
    }

    @Test
    fun `draw below the floor returns null instead of an absurd figure`() {
        // A paused game draws ~0 W; without the floor this would divide out to days.
        assertNull(batteryMinutesLeft(cap, batteryPercent = 50, smoothedWatts = 0.3f, discharging = true))
    }

    @Test
    fun `draw exactly at the floor is still estimated`() {
        assertEquals(
            (cap * 0.5f / MIN_LEFT_WATTS * 60f).toInt(),
            batteryMinutesLeft(cap, batteryPercent = 50, smoothedWatts = MIN_LEFT_WATTS, discharging = true),
        )
    }

    @Test
    fun `null inputs return null`() {
        assertNull(batteryMinutesLeft(null, batteryPercent = 50, smoothedWatts = 10f, discharging = true))
        assertNull(batteryMinutesLeft(cap, batteryPercent = null, smoothedWatts = 10f, discharging = true))
        assertNull(batteryMinutesLeft(cap, batteryPercent = 50, smoothedWatts = null, discharging = true))
    }

    @Test
    fun `zero percent returns null`() {
        assertNull(batteryMinutesLeft(cap, batteryPercent = 0, smoothedWatts = 10f, discharging = true))
    }

    @Test
    fun `time to full scales with remaining headroom`() {
        // Half empty: 30 Wh × 0.5 = 15 Wh / 10 W = 90 min to full.
        assertEquals(90, batteryMinutesToFull(cap, batteryPercent = 50, chargeWatts = 10f))
        // Nearly empty fills slower-to-full (more headroom): 30 Wh / 10 W = 180 min.
        assertEquals(180, batteryMinutesToFull(cap, batteryPercent = 0, chargeWatts = 10f))
    }

    @Test
    fun `already full returns null`() {
        assertNull(batteryMinutesToFull(cap, batteryPercent = 100, chargeWatts = 10f))
    }

    @Test
    fun `trickle charge below the floor returns null`() {
        assertNull(batteryMinutesToFull(cap, batteryPercent = 95, chargeWatts = 0.2f))
    }

    @Test
    fun `time to full null inputs return null`() {
        assertNull(batteryMinutesToFull(null, batteryPercent = 50, chargeWatts = 10f))
        assertNull(batteryMinutesToFull(cap, batteryPercent = null, chargeWatts = 10f))
        assertNull(batteryMinutesToFull(cap, batteryPercent = 50, chargeWatts = null))
    }

    @Test
    fun `Full preset shows every element`() {
        assertEquals(OverlayElement.entries.toSet(), OverlayPreset.FULL.elements)
    }

    @Test
    fun `battery-left is in every preset's default bundle`() {
        OverlayPreset.entries.forEach { preset ->
            assertTrue("BATTERY_LEFT missing from ${preset.name}", OverlayElement.BATTERY_LEFT in preset.elements)
        }
    }

    @Test
    fun `Compact bundle omits the Full-only core bars`() {
        assertTrue(OverlayElement.CORE_BARS !in OverlayPreset.COMPACT.elements)
    }
}
