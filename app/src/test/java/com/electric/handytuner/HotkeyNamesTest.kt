// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.view.KeyEvent.KEYCODE_BUTTON_C
import android.view.KeyEvent.KEYCODE_BUTTON_R1
import android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
import android.view.KeyEvent.KEYCODE_BUTTON_THUMBR
import android.view.KeyEvent.KEYCODE_BUTTON_Z
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android's own key names are a stub in JVM tests, so the rest are named "K<code>" here. */
class HotkeyNamesTest {
    private val other: (Int) -> String = { if (it == KEYCODE_BUTTON_R1) "R1" else "K$it" }

    @Test fun theDefaultHudComboReadsAsTheOdinLabelsIt() =
        assertEquals("Both paddles + L3 + R3", Hotkey.pretty(Hotkey.HUD.default, other))

    @Test fun theDefaultMenuCombo() =
        assertEquals("R1 + L3 + R3", Hotkey.pretty(Hotkey.MENU.default, other))

    @Test fun onePaddleIsNamedBySide() {
        assertEquals("Left paddle + R1", Hotkey.pretty(setOf(KEYCODE_BUTTON_R1, KEYCODE_BUTTON_C), other))
        assertEquals("Right paddle + L3", Hotkey.pretty(setOf(KEYCODE_BUTTON_THUMBL, KEYCODE_BUTTON_Z), other))
        assertEquals("R3", Hotkey.buttonName(KEYCODE_BUTTON_THUMBR, other))
    }
}
