package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FPS comes from SurfaceFlinger TimeStats, reduced in-shell to one line
 * "FR <gFrames> AF <gFps> WORST <ms> SLOW <n> LF <lFrames> LAF <lFps>", parsed by
 * [FpsReader.parseTimestats]. `AF` is the display present rate (preferred); `LAF` is the per-layer
 * submit-rate fallback.
 */
class FpsReaderTest {

    @Test
    fun prefersGlobalPresentRate() {
        // Display presents ~90 (AF) while the layer submits ~63 (LAF) — report the present rate.
        val s = FpsReader.parseTimestats("FR 87 AF 90.50 WORST 16 SLOW 0 LF 87 LAF 63.00")!!
        assertEquals(90.50f, s.avgFps)
        assertEquals(87L, s.frames)
        assertEquals(16, s.worstFrameMs)
        assertEquals(0, s.slowFrames)
    }

    @Test
    fun fallsBackToLayerWhenGlobalEmpty() {
        // No global present data (FR 0 / AF 0.00) — use the busiest layer's submit rate.
        val s = FpsReader.parseTimestats("FR 0 AF 0.00 WORST 50 SLOW 3 LF 87 LAF 63.00")!!
        assertEquals(63.00f, s.avgFps)
        assertEquals(87L, s.frames)
        assertEquals(3, s.slowFrames)
    }

    @Test
    fun parsesWhenEmbeddedInOtherOutput() {
        val s = FpsReader.parseTimestats("noise\nFR 59 AF 58.10 WORST 20 SLOW 1 LF 59 LAF 58.00\nmore")!!
        assertEquals(58.10f, s.avgFps)
        assertEquals(59L, s.frames)
    }

    @Test
    fun returnsNullWhenNothingUsable() {
        // No global frames and no layer frames — not usable.
        assertNull(FpsReader.parseTimestats("FR 0 AF 0.00 WORST 0 SLOW 0 LF 0 LAF 0.00"))
    }

    @Test
    fun returnsNullForMalformed() {
        assertNull(FpsReader.parseTimestats(""))
        assertNull(FpsReader.parseTimestats("NOFPS"))
        assertNull(FpsReader.parseTimestats("FR x AF y WORST z SLOW w LF a LAF b"))
    }
}
