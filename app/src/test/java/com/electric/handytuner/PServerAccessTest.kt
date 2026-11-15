// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Found but blocked" (the Odin 2 Mini, issue #4) must not read as working or as missing. */
class PServerAccessTest {
    @Test fun works() = assertEquals(PServer.Access.OK, PServer.access(true, "ok"))
    @Test fun foundButBlocked() = assertEquals(PServer.Access.BLOCKED, PServer.access(true, null))
    @Test fun foundButWrongReply() = assertEquals(PServer.Access.BLOCKED, PServer.access(true, ""))
    @Test fun notFound() = assertEquals(PServer.Access.MISSING, PServer.access(false, null))
}
