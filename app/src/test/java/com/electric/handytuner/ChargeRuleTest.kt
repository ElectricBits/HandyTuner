// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeRuleTest {
    private val s = ChargeRule.Settings(limit = 80, gamingBypass = true)

    @Test fun unplugged() {
        assertEquals(null, ChargeRule.decide(s, false, 0, false, false))
    }

    @Test fun pluggedGaming() {
        assertEquals(true, ChargeRule.decide(s, true, 0, true, false))
    }

    @Test fun pluggedNotGamingLevel85() {
        assertEquals(true, ChargeRule.decide(s, true, 85, false, false))
    }

    @Test fun pluggedNotGamingLevel70() {
        assertEquals(false, ChargeRule.decide(s, true, 70, false, false))
    }

    @Test fun pluggedNotGamingLevel78BypassedNowTrue() {
        assertEquals(true, ChargeRule.decide(s, true, 78, false, true))
    }

    @Test fun pluggedNotGamingLevel78BypassedNowFalse() {
        assertEquals(false, ChargeRule.decide(s, true, 78, false, false))
    }

    @Test fun settings100PluggedNotGamingLevel100() {
        val s = ChargeRule.Settings(limit = 100)
        assertEquals(false, ChargeRule.decide(s, true, 100, false, false))
    }
}