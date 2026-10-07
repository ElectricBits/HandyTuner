// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBenchTest {
    @Test fun parsesPingAndSumsUp() {
        val out = """64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=40.1 ms
64 bytes from 1.1.1.1: icmp_seq=2 ttl=57 time=42.3 ms
64 bytes from 1.1.1.1: icmp_seq=4 ttl=57 time=90.0 ms
--- 1.1.1.1 ping statistics ---
4 packets transmitted, 3 received, 25% packet loss"""
        val s = NetBench.stats(NetBench.parse(out), 4)
        assertEquals(3, s.n)
        assertEquals(42.3, s.median, 0.01)
        assertEquals(90.0, s.p99, 0.01)
        assertEquals(25.0, s.lossPct, 0.01)
        assertEquals((2.2 + 47.7) / 2, s.jitter, 0.01)
    }

    @Test fun noRepliesIsAllLost() {
        assertEquals(100.0, NetBench.stats(emptyList(), 10).lossPct, 0.0)
    }

    @Test fun verdictNamesTheRealProblems() {
        // Numbers from the Odin, 2026-09-30, Low Latency off: router spikes to ~60 ms.
        val router = NetBench.Stats(100, 6.0, 26.3, 62.9, 5.4, 0.0)
        val net = NetBench.Stats(100, 41.1, 90.6, 92.3, 10.9, 0.0)
        val v = NetBench.verdict(router, net, null, "5G", 20)
        assertTrue(v.toString(), v.single().contains("Low Latency"))
        // With it on: nothing to fix.
        val calm = NetBench.Stats(100, 4.5, 6.6, 8.7, 1.0, 0.0)
        assertTrue(NetBench.verdict(calm, NetBench.Stats(100, 39.3, 43.3, 44.7, 2.4, 0.0), null, "5G", 20).single().startsWith("All good"))
        // Bufferbloat and 2.4 GHz are both called out.
        val loaded = NetBench.Stats(100, 150.0, 220.0, 250.0, 30.0, 0.0)
        val v2 = NetBench.verdict(calm, NetBench.Stats(100, 39.3, 43.3, 44.7, 2.4, 0.0), loaded, "2.4G", 20)
        assertTrue(v2.any { "bufferbloat" in it } && v2.any { "2.4 GHz" in it })
    }
}
