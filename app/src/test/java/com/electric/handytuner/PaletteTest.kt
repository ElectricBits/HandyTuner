// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the light HUD needs: an accent picked for a black box must still read on a white one.
 * [Palette] keeps the table out of this; the rule itself is pure, so it needs no Android.
 */
class PaletteTest {

    private fun brightness(v: Int) = (v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)

    @Test fun darkKeepsThePickedColour() {
        listOf(Palette.GOOD_D, Palette.CYAN_D, Palette.MAGENTA_D, 0xFFFFFFFF.toInt()).forEach {
            assertEquals(it, Palette.accent(it, false))
        }
    }

    @Test fun theWhiteAccentBecomesTheTextColour() {
        assertEquals(Palette.TEXT_L, Palette.accent(0xFFFFFFFF.toInt(), true))
        assertEquals(Palette.TEXT_L, Palette.accent(0xFFF4F4F4.toInt(), true))     // near-white too
    }

    @Test fun neonsComeOutDarker() {
        listOf(Palette.GOOD_D, Palette.CYAN_D, Palette.MAGENTA_D, Palette.ORANGE_D, Palette.BLUE_D).forEach { c ->
            assertTrue("${Integer.toHexString(c)} stayed bright", brightness(Palette.accent(c, true)) < brightness(c))
        }
    }

    @Test fun shadingKeepsHueAndAlpha() {
        val dim = 0x800389FB.toInt()                                                // a dimmed accent, as the HUD uses
        val shaded = Palette.accent(dim, true)
        assertEquals(0x80, shaded ushr 24)                                          // its own dimming survives
        assertEquals(0.42, brightness(shaded).toDouble() / brightness(dim), 0.05)   // still recognisably the same colour
    }
}
