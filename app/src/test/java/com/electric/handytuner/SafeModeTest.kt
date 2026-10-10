// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe mode's trigger, [SafeMode.tripAfter] — the rule [SafeMode.recordStart] applies, split out
 * because that needs a files dir to remember the window in and this doesn't.
 *
 * The window lives in a file rather than memory because the crash loop being watched for is exactly
 * the case where the process doesn't survive to keep anything: see the last test.
 */
class SafeModeTest {

    private fun trip(starts: List<Long>, now: Long, windowMs: Long = WINDOW) = SafeMode.tripAfter(starts, now, windowMs)

    @Test fun doesNotTripOnAFirstStart() {
        assertFalse(trip(emptyList(), 1_000_000L))
    }

    @Test fun doesNotTripOnASecondStart() {
        assertFalse(trip(listOf(1_000_000L), 1_060_000L))
    }

    @Test fun tripsOnTheThirdStartInsideTheWindow() {
        assertTrue(trip(listOf(1_000_000L, 1_120_000L), 1_240_000L))
    }

    @Test fun tripsEvenWhenTheStartsAreSecondsApart() {
        // The real shape: a low-memory kill restarting the service over and over, a few seconds each.
        assertTrue(trip(listOf(0L, 3_000L, 6_000L), 9_000L))
    }

    @Test fun forgetsStartsThatHaveFallenOutOfTheWindow() {
        // An overlay started every morning must not eventually trip safe mode.
        assertFalse(trip(listOf(0L, DAY, 2 * DAY, 3 * DAY), 4 * DAY))
    }

    @Test fun countsOnlyTheStartsStillInTheWindow() {
        // Two from yesterday and one from now: only one of them counts, so this is the second start.
        assertFalse(trip(listOf(0L, DAY, 1_380_000L), 1_400_000L))
        // Two from yesterday and two from now: this is the third start inside the window.
        assertTrue(trip(listOf(0L, DAY, 1_380_000L, 1_400_000L), 1_420_000L))
    }

    @Test fun theWindowEdgeIsExclusive() {
        val at = 1_600_000L
        // Exactly on the edge counts as out, so two old plus this one must not trip.
        assertFalse(trip(listOf(at - WINDOW, at - WINDOW + 1), at))
    }

    @Test fun threeStartsInThreeProcessesIsStillThreeStarts() {
        // Each start in its own process is the normal case for a crash loop, which is why the count
        // has to be carried on disk: these are the totals a file would hold across the restarts.
        val carried = listOf(1_000_000L, 1_002_000L, 1_004_000L)
        assertTrue("the third start should trip", trip(carried.dropLast(1), carried.last()))
    }

    private companion object {
        const val WINDOW = 10 * 60_000L
        const val DAY = 24 * 60 * 60_000L
    }

    @Test fun anUpdateStartsTheCountAgain() {
        val starts = listOf(1_000L, 2_000L)
        assertEquals(starts, SafeMode.startsToCount(starts, "111", "111"))
        assertEquals(emptyList<Long>(), SafeMode.startsToCount(starts, "111", "222"))   // reinstalled since
        assertEquals(emptyList<Long>(), SafeMode.startsToCount(starts, null, "222"))    // first run of this rule
    }

    @Test fun aReconnectInTheSameProcessIsNotARestart() {
        // Another accessibility client (a UI dump, another assistant app) makes Android hand the service
        // back without the process ever dying. That isn't the crash loop this watches for.
        assertFalse(SafeMode.isNewProcess("4711", 4711))
        assertTrue(SafeMode.isNewProcess("4711", 4712))
        assertTrue(SafeMode.isNewProcess(null, 4712))
    }
}
