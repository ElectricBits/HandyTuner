// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsRankTest {
    private fun r(name: String, ms: List<Int>, lost: Int = 0) = Net.DnsResult(name, "", null, ms, lost)

    @Test fun fewestFailuresThenLowestMedian() {
        val ranked = Net.rank(listOf(
            r("spiky", listOf(10, 11, 12, 300, 12)),          // median 12, average 69
            r("steady", listOf(20, 21, 22, 20, 21)),
            r("fast but lossy", listOf(5, 5, 5), lost = 2),
            r("dead", emptyList(), lost = 10),
        ))
        assertEquals(listOf("spiky", "steady", "fast but lossy", "dead"), ranked.map { it.name })
    }
}
