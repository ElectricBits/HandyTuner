// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test fun roundTripsAndSkipsJunk() {
        val m = mapOf(
            "app.gamenative:SlimeRancher.exe" to GameSettings.of(Preset.BATTERY),
            "com.mojang.minecraftpe" to GameSettings.of(Preset.BALANCED).copy(pulse = PulsePart(120, null, 0, 5)),
        )
        assertEquals(m, GameStore.parse(GameStore.render(m), emptyList()))
        // A bad entry (both AutoTDP and a tier) and an unknown preset are dropped, the good one kept.
        val junk = """{"v":1,"games":{"a":{"preset":"BALANCED","autoFps":60,"tier":1,"cap":0,"fan":4,"profile":"NORMAL"},
            "b":{"preset":"TURBO","autoFps":60,"cap":0,"fan":4,"profile":"NORMAL"},
            "c":{"preset":"PERFORMANCE","tier":0,"cap":0,"fan":5,"profile":"NORMAL"}}}"""
        assertEquals(setOf("c"), GameStore.parse(junk, emptyList()).keys)
        assertEquals(emptyMap<String, GameSettings>(), GameStore.parse("not json", emptyList()))
    }

    @Test fun importRefusesBadValuesAndRenamesClashes() {
        val file = """{"v":1,"presets":[
            {"name":"Quiet 40","tier":1,"cap":40,"fan":1,"profile":"NORMAL","evil":"rm -rf /"},
            {"name":"Crash","tier":1,"cap":24,"fan":1,"profile":"NORMAL"},
            {"name":"Both","autoFps":60,"tier":1,"cap":0,"fan":4,"profile":"NORMAL"},
            {"name":"Optimal","autoFps":60,"cap":0,"fan":4,"profile":"NORMAL"},
            {"name":"${"x".repeat(80)}","autoFps":90,"cap":0,"fan":5,"profile":"LOW_PING"}],
            "games":{"com.mojang.minecraftpe":{"preset":"custom:Quiet 40","tier":1,"cap":40,"fan":1,"profile":"NORMAL"},
                     "new.game":{"preset":"PERFORMANCE","tier":0,"cap":0,"fan":5,"profile":"NORMAL"}}}"""
        val mine = listOf(PresetDef("custom:Quiet 40", "Quiet 40", PulsePart(null, 1, 30, 1), Profile.NORMAL))
        val inc = CustomPresets.merge(file, mine, mapOf("com.mojang.minecraftpe" to GameSettings.of(Preset.BALANCED)))!!
        // The 24 fps cap and the AutoTDP+tier mix are refused; names clash-renamed and cut to 40.
        assertEquals(listOf("Quiet 40 (2)", "Optimal (2)", "x".repeat(40)), inc.presets.map { it.label })
        assertEquals(2, inc.skipped)
        // An existing game is never overwritten by an import.
        assertEquals(setOf("new.game"), inc.games.keys)
        assertEquals(null, CustomPresets.merge("x".repeat(CustomPresets.MAX_BYTES + 1), mine, emptyMap()))
        // Re-importing your own export adds nothing.
        assertEquals(emptyList<PresetDef>(), CustomPresets.merge(CustomPresets.render(mine, emptyMap()), mine, emptyMap())!!.presets)
    }

    @Test fun theOldLowLatencyPresetIsNowCompetitive() {
        val old = """{"v":1,"games":{"com.mojang.minecraftpe":{"preset":"LOW_LATENCY","tier":0,"cap":0,"fan":5,"profile":"LOW_PING"}}}"""
        assertEquals(Preset.COMPETITIVE.def, GameStore.parse(old, emptyList()).getValue("com.mojang.minecraftpe").preset)
    }

    @Test fun tweakedOnlyWhenChanged() {
        assertFalse(GameSettings.of(Preset.PERFORMANCE).tweaked)
        assertTrue(GameSettings.of(Preset.PERFORMANCE).copy(profile = Profile.LOW_PING).tweaked)
    }

    @Test fun oldProfilesMapToPresets() {
        assertEquals(Preset.BALANCED.def, GameStore.fromOldProfile(Profile.LOW_PING).preset)
        assertEquals(Preset.BATTERY.def, GameStore.fromOldProfile(Profile.BATTERY_SAVER).preset)
        assertEquals(Preset.BALANCED.def, GameStore.fromOldProfile(Profile.NORMAL).preset)
    }

    /** Hold temp (PULSE's fan mode 6) is a preset fan choice since stage 4; other unknown modes still aren't. */
    @Test fun holdTempFanIsAccepted() {
        assertTrue(PulsePart(null, 0, 0, Actions.FAN_CUSTOM).valid)
        assertEquals("Hold temp", Actions.FAN_NAMES[Actions.FAN_CUSTOM])
        assertFalse(PulsePart(null, 0, 0, 2).valid)
    }
}
