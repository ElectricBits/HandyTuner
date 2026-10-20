// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Accents are the same on both themes; only White turns into the text color on the light one (white on white).
 * [Palette] keeps the table out of this; the rule itself is pure, so it needs no Android.
 */
class PaletteTest {


    @Test fun darkKeepsThePickedColour() {
        listOf(Palette.GOOD_D, Palette.CYAN_D, Palette.MAGENTA_D, 0xFFFFFFFF.toInt()).forEach {
            assertEquals(it, Palette.accent(it, false))
        }
    }

    @Test fun theWhiteAccentBecomesTheTextColour() {
        assertEquals(Palette.TEXT_L, Palette.accent(0xFFFFFFFF.toInt(), true))
        assertEquals(Palette.TEXT_L, Palette.accent(0xFFF4F4F4.toInt(), true))     // near-white too
    }

    @Test fun neonsStayTheSameOnLight() {
        listOf(Palette.GOOD_D, Palette.CYAN_D, Palette.MAGENTA_D, Palette.ORANGE_D, Palette.BLUE_D, 0xFFFFEE00.toInt(), 0x800389FB.toInt()).forEach {
            assertEquals(Integer.toHexString(it), it, Palette.accent(it, true))
        }
    }
}
