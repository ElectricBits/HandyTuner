// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningNoteTest {
    /** Measured on the Odin: Wind Waker below its fps target sat at 16% for minutes. That must not show. */
    @Test fun stuckNeverShows() {
        val n = LearningNote()
        assertEquals(null, n.shown(16, 0))
        assertEquals(null, n.shown(16, 60_000))
    }

    @Test fun showsWhileRisingThenHides() {
        val n = LearningNote()
        n.shown(16, 0)
        assertEquals(25, n.shown(25, 10_000))
        assertEquals(25, n.shown(25, 10_000 + LearningNote.FRESH_MS - 1))
        assertEquals(null, n.shown(25, 10_000 + LearningNote.FRESH_MS))
        assertEquals(33, n.shown(33, 200_000))
    }

    @Test fun offOrLearnedHides() {
        val n = LearningNote()
        n.shown(16, 0); n.shown(25, 1_000)
        assertEquals(null, n.shown(null, 2_000))
    }
}
