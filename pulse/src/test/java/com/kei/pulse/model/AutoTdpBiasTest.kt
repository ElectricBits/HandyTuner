package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTdpBiasTest {

    @Test
    fun `per-app bias overrides the global default`() {
        assertEquals(
            AutoTdpBias.EFFICIENT,
            AutoTdpBias.resolve(perApp = AutoTdpBias.EFFICIENT, global = AutoTdpBias.SMOOTH),
        )
    }

    @Test
    fun `a null per-app bias inherits the global default`() {
        assertEquals(
            AutoTdpBias.SMOOTH,
            AutoTdpBias.resolve(perApp = null, global = AutoTdpBias.SMOOTH),
        )
    }

    @Test
    fun `the global default bias is EFFICIENT`() {
        assertEquals(AutoTdpBias.EFFICIENT, AppSettings().autoTdpBias)
    }
}
