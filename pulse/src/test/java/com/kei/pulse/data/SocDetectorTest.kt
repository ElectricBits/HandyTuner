package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocDetectorTest {

    @Test
    fun mapsKnownCodenamesToFriendlyNames() {
        assertEquals("Dragonwing Q8", SocDetector.displayName("CQ8725S")) // AYN Odin 3
        assertEquals("SD 8 Gen 2", SocDetector.displayName("QCS8550"))    // RP6 / AYN Thor
    }

    @Test
    fun isCaseInsensitiveAndTrims() {
        assertEquals("Dragonwing Q8", SocDetector.displayName(" cq8725s "))
    }

    @Test
    fun fallsBackToRawCodenameWhenUnmapped() {
        assertEquals("SM8650", SocDetector.displayName("SM8650"))
    }

    @Test
    fun returnsNullForNullOrBlank() {
        assertNull(SocDetector.displayName(null))
        assertNull(SocDetector.displayName("   "))
    }
}
