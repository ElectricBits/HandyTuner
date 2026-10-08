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

    /** Captured off the Odin docked to a TV: the game on the TV (5), Cocoon on its own screen (0). */
    @Test fun topOfEachScreen() {
        val out = "Display #5 (activities from top to bottom):;    topResumedActivity=ActivityRecord{8c83d8 u0 app.gamenative/.MainActivity} t218};" +
            "Display #0 (activities from top to bottom):;      topResumedActivity=ActivityRecord{235e4be u0 rip.moth.cocoonshell/.ExternalDisplayActivity} t203};"
        assertEquals("app.gamenative", com.kei.pulse.appwatch.ScreenTop.parse(out, 5))
        assertEquals("rip.moth.cocoonshell", com.kei.pulse.appwatch.ScreenTop.parse(out, 0))
        assertNull(com.kei.pulse.appwatch.ScreenTop.parse(out, 7))
        assertNull(com.kei.pulse.appwatch.ScreenTop.parse("Display #5 (activities from top to bottom):;Display #0 (…):;", 5))   // nothing resumed on the TV
    }

    /** Captured off the Odin docked to a 4K TV. */
    @Test fun tvForScreenshots() {
        val out = "Display 4630946904417961859 (HWC display 0): port=131 pnpId=QCM displayName=\"\";" +
            "Display 4620873314284888069 (HWC display 1): port=5 pnpId=HEC displayName=\"HISENSE\";"
        assertEquals("4620873314284888069", Actions.externalDisplayId(out))
        assertNull(Actions.externalDisplayId("Display 4630946904417961859 (HWC display 0): port=131;"))
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
