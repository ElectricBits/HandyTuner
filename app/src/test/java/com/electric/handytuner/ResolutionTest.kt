// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionTest {
    private val odin = Resolution.Spec(1080, 1920, 369)
    /** The 4K TV as `wm` reports it on the Odin: sideways. */
    private val tv = Resolution.Spec(2160, 3840, 640)

    @Test fun screenScale() {
        assertNull(Resolution.scaled(odin, 100, true))
        assertEquals(Resolution.Spec(810, 1440, 276), Resolution.scaled(odin, 75, true))
        assertEquals(Resolution.Spec(540, 960, 369), Resolution.scaled(odin, 50, false))
    }

    @Test fun tvByHeight() {
        assertEquals(Resolution.Spec(1080, 1920, 320), Resolution.atHeight(tv, 1080, true))
        assertEquals(Resolution.Spec(1440, 2560, 640), Resolution.atHeight(tv, 1440, false))
        assertEquals(Resolution.Spec(1080, 1920, 640), Resolution.atHeight(Resolution.Spec(3840, 2160, 640), 1080, false).let { it!!.copy(w = it.h, h = it.w) })
        assertNull(Resolution.atHeight(tv, 0, true))          // the TV's own
        assertNull(Resolution.atHeight(Resolution.Spec(1080, 1920, 320), 1080, true))   // a 1080p TV is already there
    }

    @Test fun parsesWm() {
        assertEquals(tv, Resolution.parse("Physical size: 2160x3840\nOverride size: 1080x1920", "Physical density: 640"))
        assertNull(Resolution.parse("", ""))
    }
}
