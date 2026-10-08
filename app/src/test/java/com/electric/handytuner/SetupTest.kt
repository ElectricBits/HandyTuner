// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class SetupTest {
    @Test fun setupFromDockAndPad() {
        assertEquals(Setup.HANDHELD, Setup.of(docked = false, pad = false))
        assertEquals(Setup.DOCKED, Setup.of(docked = true, pad = false))
        assertEquals(Setup.PAD, Setup.of(docked = false, pad = true))
        assertEquals(Setup.COUCH, Setup.of(docked = true, pad = true))
    }

    @Test fun dockedByDisplayDockStateOrCharger() {
        assertTrue(Dock.decide(1, null, plugged = false, chargerIsDock = false))
        assertTrue(Dock.decide(0, 1, plugged = false, chargerIsDock = false))          // EXTRA_DOCK_STATE_DESK
        assertFalse(Dock.decide(0, 0, plugged = true, chargerIsDock = false))          // charging alone isn't docked…
        assertTrue(Dock.decide(0, 0, plugged = true, chargerIsDock = true))            // …unless asked
        assertFalse(Dock.decide(0, null, plugged = false, chargerIsDock = true))
    }

    private val gamepad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK

    @Test fun externalPadDetection() {
        // The system's answer wins when it can be read.
        assertTrue(Pads.isExternalPad(gamepad, false, true, false, 0x1234, "Some pad", emptySet()))
        assertFalse(Pads.isExternalPad(gamepad, false, false, true, Pads.VENDOR_8BITDO, "8BitDo", emptySet()))
        // Without it: a battery or a known maker.
        assertTrue(Pads.isExternalPad(gamepad, false, null, false, Pads.VENDOR_8BITDO, "8BitDo Ultimate 2C", emptySet()))
        assertTrue(Pads.isExternalPad(gamepad, false, null, true, 0x1234, "Pad", emptySet()))
        assertFalse(Pads.isExternalPad(gamepad, false, null, false, 0x1234, "Odin Controller", emptySet()))
        // Never: not a gamepad, virtual, or marked as not a controller.
        assertFalse(Pads.isExternalPad(InputDevice.SOURCE_KEYBOARD, false, true, true, Pads.VENDOR_8BITDO, "Keys", emptySet()))
        assertFalse(Pads.isExternalPad(gamepad, true, true, true, Pads.VENDOR_8BITDO, "Virtual", emptySet()))
        assertFalse(Pads.isExternalPad(gamepad, false, true, true, Pads.VENDOR_8BITDO, "8BitDo", setOf("8BitDo")))
    }

    @Test fun brands() {
        assertEquals("8BitDo", Pads.brand(Pads.VENDOR_8BITDO, "x"))
        assertEquals("8BitDo", Pads.brand(0, "8BitDo Ultimate 2C Wireless"))
        assertEquals("Xbox", Pads.brand(Pads.VENDOR_MICROSOFT, "x"))
        assertEquals(null, Pads.brand(0, "Generic"))
    }

    @Test fun presetForEachSetup() {
        val r = SetupRules(dockedPreset = "PERFORMANCE", padPreset = "BATTERY")
        assertEquals(null, r.presetFor(Setup.HANDHELD))
        assertEquals("PERFORMANCE", r.presetFor(Setup.DOCKED))
        assertEquals("BATTERY", r.presetFor(Setup.PAD))
        assertEquals("PERFORMANCE", r.presetFor(Setup.COUCH))                       // couch falls back to docked
        assertEquals("COMPETITIVE", r.copy(couchPreset = "COMPETITIVE").presetFor(Setup.COUCH))
        assertEquals("BATTERY", SetupRules(padPreset = "BATTERY").presetFor(Setup.COUCH))   // then to the controller one
    }

    @Test fun effectiveSettings() {
        val presets = Preset.entries.map { it.def }
        val own = GameSettings.of(Preset.BALANCED)
        val r = SetupRules(dockedPreset = "PERFORMANCE")
        assertEquals(own, r.effective(own, Setup.HANDHELD, presets))
        assertEquals(GameSettings.of(Preset.PERFORMANCE), r.effective(own, Setup.DOCKED, presets))
        // A preset that no longer exists (a deleted custom one) leaves the game's own.
        assertEquals(own, SetupRules(dockedPreset = "custom:gone").effective(own, Setup.DOCKED, presets))
    }

    @Test fun rulesRoundTrip() {
        val r = SetupRules(dockedPreset = "PERFORMANCE", couchPreset = "custom:Couch", chargerIsDock = true, tvHud = false,
            dimScreen = true, keepAwake = true, sleepOff = false, ignoreBuiltIn = true, hideMarkers = true, padAlerts = false,
            launchOnDock = "com.retroarch", ignoredPads = setOf("Odin Controller", "gpio-keys"))
        assertEquals(r, SetupRules.from(r.toProperties()))
        assertEquals(SetupRules(), SetupRules.from(Properties()))
    }

    @Test fun rulesRejectBadValues() {
        val p = Properties().apply {
            setProperty("tvHud", "maybe"); setProperty("launchOnDock", "rm -rf /"); setProperty("dockedPreset", "  ")
        }
        val r = SetupRules.from(p)
        assertEquals(SetupRules().tvHud, r.tvHud)
        assertEquals(null, r.launchOnDock)
        assertEquals(null, r.dockedPreset)
    }

    @Test fun padActionsParse() {
        val p = Properties().apply {
            setProperty("100", "SCREENSHOT"); setProperty("101", "NOPE"); setProperty("x", "HUD"); setProperty("102", "QUICK_MENU")
        }
        assertEquals(mapOf(100 to PadAction.SCREENSHOT, 102 to PadAction.QUICK_MENU), PadAction.parse(p))
    }

    @Test fun keymapSlots() {
        assertEquals("com.game", KeyMapper.slot("com.game", false))
        assertEquals("com.game#pad", KeyMapper.slot("com.game", true))
    }
}
