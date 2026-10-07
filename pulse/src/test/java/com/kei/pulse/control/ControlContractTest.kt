// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.kei.pulse.control

import com.kei.pulse.model.PowerTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** HandyTuner sends tier numbers and fan modes over IPulseControl; reordering these breaks it silently. */
class ControlContractTest {
    @Test fun tierNumbersMatchTheInterface() {
        assertEquals(listOf(PowerTier.MAX, PowerTier.BALANCED, PowerTier.POWER_SAVING, PowerTier.CUSTOM), PowerTier.entries.toList())
    }

    /** A 24 fps override hung the Odin: only measured caps may pass. */
    @Test fun onlyMeasuredCapsAreAccepted() {
        assertEquals(setOf(30, 40, 60), ControlService.CAPS)
    }

    @Test fun onlyAynFanModesAreAccepted() {
        assertEquals(setOf(1, 4, 5, 6), ControlService.FAN_MODES)
    }
}
