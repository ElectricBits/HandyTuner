// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomIdTest {
    /** Real roms.sh output, captured on the Odin 2 Portal: Wind Waker started from Cocoon in Dolphin. */
    private val windWaker = "document/primary%3AROMs%2Fgc%2FLegend%20of%20Zelda%2C%20The%20-%20The%20Wind%20Waker%20(USA%2C%20Canada).nkit.iso"

    @Test fun readsTheGameFile() = assertEquals("Legend of Zelda, The - The Wind Waker (USA, Canada)", GameId.rom(windWaker))
    @Test fun niceName() = assertEquals("The Legend of Zelda - The Wind Waker",
        GameId.label("org.dolphinemu.dolphinemu:Legend of Zelda, The - The Wind Waker (USA, Canada)"))
    @Test fun psp() = assertEquals("Crisis Core - Final Fantasy VII", GameId.rom("document/primary%3AROMs%2Fpsp%2FCrisis%20Core%20-%20Final%20Fantasy%20VII.cso"))
    @Test fun plusSign() = assertEquals("Pikmin 2+", GameId.rom("document/primary%3AROMs%2Fgc%2FPikmin%202%2B.rvz"))
    @Test fun notAGameFile() = assertNull(GameId.rom("document/primary%3AMovies%2Fclip.mp4"))
    @Test fun archivesOnlyForEmulators() {
        assertNull(GameId.rom("document/primary%3ADownload%2Fstuff.zip"))
        assertEquals("Sonic", GameId.rom("document/primary%3AROMs%2Fmegadrive%2FSonic.zip", archivesOk = true))
    }
    @Test fun nothing() = assertNull(GameId.rom(""))
    @Test fun windowsLabelUnchanged() = assertEquals("SlimeRancher", GameId.label("app.gamenative:SlimeRancher.exe"))
    @Test fun plainPackage() = assertNull(GameId.label("org.dolphinemu.dolphinemu"))
}
