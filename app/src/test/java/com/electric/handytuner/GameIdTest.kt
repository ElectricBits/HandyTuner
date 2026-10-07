// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameIdTest {
    /** Captured off the Odin with Slime Rancher running in GameNative. */
    @Test fun picksTheGameOverSteamAndWine() {
        val out = "C:\\Program Files (x86)\\Steam\\steamclient_loader_x64.exe;" +
            "C:\\windows\\system32\\services.exe;" +
            "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Slime Rancher\\SlimeRancher.exe +fps_max 30 +fps_max 30;"
        assertEquals("SlimeRancher.exe", GameId.exe(out))
    }

    @Test fun onlyHelpersMeansNoGameYet() {
        assertNull(GameId.exe("C:\\windows\\explorer.exe /desktop;C:\\windows\\system32\\winedevice.exe;"))
        assertNull(GameId.exe(""))
    }

    @Test fun label() {
        assertEquals("SlimeRancher", GameId.label("app.gamenative:SlimeRancher.exe"))
        assertNull(GameId.label("com.some.game"))
    }
}
