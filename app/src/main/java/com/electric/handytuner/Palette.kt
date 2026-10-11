// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

/**
 * Every colour, dark and light, as plain ints: [Hand] wraps them for the Compose app, the HUD and the
 * Quick Menu (plain Views) read them directly. Keeping the pairs side by side makes it obvious whether
 * a light value still carries the dark one's hue.
 *
 * The light values keep the same hues, dark enough to read on white; the dark navies and blacks become
 * white and navy-on-white.
 */
object Palette {
    // Panels
    val BG_D = 0xFF000000.toInt(); val BG_L = 0xFFEEF2F8.toInt()              // behind the cards
    val CARD_D = 0xFF0A1428.toInt(); val CARD_L = 0xFFFFFFFF.toInt()          // cards, helper bubble
    val TILE_D = 0xCC0A1428.toInt(); val TILE_L = 0xE6FFFFFF.toInt()          // Quick Menu glass tile
    val TILE2_D = 0xFF111E38.toInt(); val TILE2_L = 0xFFE4EBF5.toInt()        // tile inside a tile
    val PANEL_D = 0xF0040914.toInt(); val PANEL_L = 0xF2FFFFFF.toInt()        // menu panel, onboarding card
    val HUD_D = 0x050510.toInt(); val HUD_L = 0xFFFFFF.toInt()                // HUD box, under the opacity
    val GRAD_D = 0xFF06214A.toInt(); val GRAD_L = 0xFFDCE6F5.toInt()          // onboarding backdrop

    // Text
    val TEXT_D = 0xFFFFFFFF.toInt(); val TEXT_L = 0xFF0A1428.toInt()
    val TEXT_DIM_D = 0x99FFFFFF.toInt(); val TEXT_DIM_L = 0x990A1428.toInt()
    val MUTED_D = 0xFF7A8BA0.toInt(); val MUTED_L = 0xFF5C6C80.toInt()

    // Controls
    val TRACK_D = 0xFF3A4660.toInt(); val TRACK_L = 0xFFC7D0DC.toInt()        // switch, off
    val BAR_D = 0xFF2A3654.toInt(); val BAR_L = 0xFFD8E0EA.toInt()            // slider behind the fill
    val GLOW_D = 0x660389FB.toInt(); val GLOW_L = 0x00000000.toInt()          // neon shadow: pointless on white

    // Accents and status colours
    val BLUE_D = 0xFF0389FB.toInt(); val BLUE_L = 0xFF0369C8.toInt()
    val BLUE_DIM_D = 0x800389FB.toInt(); val BLUE_DIM_L = 0x800369C8.toInt()
    val CYAN_D = 0xFF00E5FF.toInt(); val CYAN_L = 0xFF00707F.toInt()
    val GOOD_D = 0xFF39FF14.toInt(); val GOOD_L = 0xFF158A2E.toInt()
    val WARN_D = 0xFFFFB300.toInt(); val WARN_L = 0xFF9A6200.toInt()
    val BAD_D = 0xFFFF3B3B.toInt(); val BAD_L = 0xFFC62828.toInt()
    val MAGENTA_D = 0xFFFF2BD6.toInt(); val MAGENTA_L = 0xFFA81B8E.toInt()
    val ORANGE_D = 0xFFFF8A00.toInt(); val ORANGE_L = 0xFFA65200.toInt()

    // The AFK screen
    val AFK_BG_D = 0xFF000000.toInt(); val AFK_BG_L = 0xFFFFFFFF.toInt()
    val AFK_TEXT_D = 0xFF3A4A60.toInt(); val AFK_TEXT_L = 0xFF5C6C80.toInt()

    /**
     * A picked HUD accent on a light panel: the near-white "White" accent becomes the text colour it
     * stands for, everything else is darkened so its hue still reads. Pure, hence [PaletteTest].
     */
    fun accent(argb: Int, light: Boolean): Int = when {
        !light -> argb
        argb and 0xFFFFFF >= 0xEDEDED -> TEXT_L
        else -> shade(argb)
    }

    /** The same hue at [f] % brightness: the neon accents are unreadable on white. */
    fun shade(argb: Int, f: Int = 42): Int = (argb and 0xFF000000.toInt()) or
        ((argb shr 16 and 0xFF) * f / 100 shl 16) or ((argb shr 8 and 0xFF) * f / 100 shl 8) or ((argb and 0xFF) * f / 100)
}
