// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetTest {
    @Test fun ipv4() {
        assertEquals("34.107.172.168", Net.ipv4("A8AC6B22"))
        assertEquals("155.133.229.20", Net.ipv4("0000000000000000FFFF000014E5859B"))
        assertNull(Net.ipv4("20010DB8000000000000000000000001"))
    }

    /** Captured off the Odin with Slime Rancher running (offline game): web and Steam only. */
    @Test fun offlineGameHasNoServer() {
        val out = "tcp 00000000:0000 0A;tcp 3201A8C0:C7CA 01;tcp A8AC6B22:01BB 01;tcp 28716F22:01BB 08;" +
            "tcp6 0000000000000000FFFF000014E5859B:698C 01;udp 00000000:0000 07;"
        assertNull(Net.pick(out))
    }

    @Test fun prefersConnectedUdp() {
        // TCP game port 7777 on 1.2.3.4, UDP 27015 on 5.6.7.8
        val out = "tcp 04030201:1E61 01;udp 08070605:6987 01;"
        assertEquals("5.6.7.8", Net.pick(out))
        assertEquals("1.2.3.4", Net.pick("tcp 04030201:1E61 01;"))
    }
}
