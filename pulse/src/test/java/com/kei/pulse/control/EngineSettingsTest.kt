// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.kei.pulse.control

import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.RgbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HandyTuner's Tuning page can only change what's whitelisted, within range. */
class EngineSettingsTest {
    @Test fun fanTargetStaysInTheOwnersBand() {
        assertEquals(EngineChange.FanTarget(60), EngineSettings.parse("fanTargetC", "60"))
        assertEquals(EngineChange.FanTarget(88), EngineSettings.parse("fanTargetC", "88"))
        assertNull(EngineSettings.parse("fanTargetC", "59"))
        assertNull(EngineSettings.parse("fanTargetC", "91"))   // the owner's heat rule: never near 96 °C
        assertNull(EngineSettings.parse("fanTargetC", "hot"))
    }

    @Test fun choicesMustBeKnownNames() {
        assertEquals(EngineChange.Bias(AutoTdpBias.SMOOTH), EngineSettings.parse("autoTdpBias", "SMOOTH"))
        assertNull(EngineSettings.parse("autoTdpBias", "smooth"))
        assertEquals(EngineChange.Lights(RgbMode.HEAT), EngineSettings.parse("rgbMode", "HEAT"))
        assertEquals(EngineChange.Sleep(true), EngineSettings.parse("sleep", "on"))
        assertNull(EngineSettings.parse("sleep", "true"))
    }

    @Test fun colorIsSixHexDigitsAndAPercent() {
        assertEquals(EngineChange.LightColor(0xFFFF0000.toInt(), 50), EngineSettings.parse("rgbColor", "FF0000,50"))
        assertNull(EngineSettings.parse("rgbColor", "80FF0000,50"))   // no alpha smuggled in
        assertNull(EngineSettings.parse("rgbColor", "FF0000,101"))
        assertNull(EngineSettings.parse("rgbColor", "FF0000"))
    }

    @Test fun unknownKeysAreRefused() {
        assertNull(EngineSettings.parse("pulseEnabled", "false"))
    }
}
