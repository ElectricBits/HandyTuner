// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.view.KeyEvent.KEYCODE_BUTTON_A
import android.view.KeyEvent.KEYCODE_BUTTON_L1
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
import android.view.KeyEvent.KEYCODE_BUTTON_THUMBR
import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {
    @Test fun stepStopsAtTheEnds() {
        assertEquals(1, Nav.step(0, 1, 3))
        assertEquals(2, Nav.step(2, 1, 3))      // no wrap past the last
        assertEquals(0, Nav.step(0, -1, 3))     // nor before the first
        assertEquals(-1, Nav.step(0, 1, 0))     // nothing to pick
    }

    @Test fun nextWraps() {
        assertEquals(1, Nav.next(0, 3))
        assertEquals(0, Nav.next(2, 3))
        assertEquals(0, Nav.next(-1, 3))        // nothing picked yet: the first
    }

    @Test fun aLoneShoulderSwitches() {
        val s = Nav.Shoulder()
        s.down(KEYCODE_BUTTON_R1, othersHeld = false)
        assertEquals(1, s.up(KEYCODE_BUTTON_R1))
        s.down(KEYCODE_BUTTON_L1, othersHeld = false)
        assertEquals(-1, s.up(KEYCODE_BUTTON_L1))
    }

    @Test fun theQuickMenuHotkeyNeverSwitches() {
        val s = Nav.Shoulder()
        // Sticks first, then R1.
        s.down(KEYCODE_BUTTON_THUMBL, false); s.down(KEYCODE_BUTTON_THUMBR, true); s.down(KEYCODE_BUTTON_R1, true)
        assertEquals(0, s.up(KEYCODE_BUTTON_R1))
        // R1 first, then the sticks.
        s.down(KEYCODE_BUTTON_R1, false); s.down(KEYCODE_BUTTON_THUMBL, true)
        assertEquals(0, s.up(KEYCODE_BUTTON_R1))
    }

    @Test fun otherButtonsDoNothing() {
        val s = Nav.Shoulder()
        s.down(KEYCODE_BUTTON_A, false)
        assertEquals(0, s.up(KEYCODE_BUTTON_A))
    }
}
