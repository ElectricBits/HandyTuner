// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status note the `:overlay` process leaves for Diagnostics, and the words Diagnostics turns it into.
 * The file is written by one process and read by another, so the format has to survive being written
 * twice and read after a half-finished write.
 */
class PulseStatusTest {
    private val now = 1_800_000_000_000L
    private fun state(linked: Boolean = true, version: Int = 4, asleep: Boolean = false) =
        PulseStatus.State(linked, version, asleep, now)

    @Test fun `a note survives the trip through a file`() {
        val s = state(asleep = true)
        assertEquals(s, PulseStatus.decode(PulseStatus.encode(s)))
    }

    @Test fun `a half-written note is no note at all`() {
        // Two processes, one writing while the other reads: a truncated note must not be read as "watcher
        // asleep" (which would send the owner to fix something that isn't broken) or as "awake".
        assertNull(PulseStatus.decode("linked=true\nversion=4"))                    // cut short
        assertNull(PulseStatus.decode("linked=true\nversion=4\nwatcher=\nat=$now")) // empty field
        assertNull(PulseStatus.decode("linked=true\nversion=4\nwatcher=awake"))    // no timestamp
        assertNull(PulseStatus.decode("linked=maybe\nversion=x\nwatcher=awake\nat=nope"))
    }

    @Test fun `a note stops being believed once it is past its age`() {
        // The overlay only writes when the link answers, so a long quiet stretch is normal. What's stale is
        // "the overlay isn't running", which the row words differently from "watcher asleep".
        assertNull(PulseStatus.fresh(state(), now + PulseStatus.STALE_MS + 1))
        assertEquals(now, PulseStatus.fresh(state(), now + PulseStatus.STALE_MS)?.at)
        assertNull(PulseStatus.fresh(null, now))
    }

    @Test fun `a note whose clock ran away is not trusted`() {
        // at=0 or a note from the future: both are tampering or a badly wrong clock, not a status.
        assertEquals(now, PulseStatus.fresh(state(), now + 60_000)?.at)   // a minute of skew is tolerable
        assertNull(PulseStatus.fresh(state(), now + PulseStatus.STALE_MS + 1))
    }

    @Test fun `the row names what is in the way`() {
        assertTrue(PulseStatus.detail(null).contains("Not started"))
        assertTrue(PulseStatus.detail(state(linked = false)).contains("Not answering"))
        val d = PulseStatus.detail(state(asleep = true))
        assertTrue(d, d.contains("Usage access"))
    }

    @Test fun `everything working is the only fully settled state`() {
        assertEquals(Diagnostics.Status.OK, PulseStatus.status(state()))
        assertEquals(Diagnostics.Status.MISSING, PulseStatus.status(state(asleep = true)))
        assertEquals(Diagnostics.Status.MISSING, PulseStatus.status(null))
    }
}