package com.kei.pulse.data

import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanCurvePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Custom fan engine drives the fan as a CONTINUOUS slew, not a stepped jump: the slow telemetry tick
 * sets a target ([setTarget]); a fast loop eases the applied % toward it by a small amount each pass and
 * WRITES the duty every pass ([slew]) — that write is also the re-assert that beats the vendor fan service.
 * Asserts the ramp is gradual (so the fan doesn't audibly step), the 90°C override snaps to full for safety,
 * the floor holds, and a higher slew rate ramps faster. Live writes are verified on-device.
 */
class FanCurveControllerTest {
    private fun controller(writes: MutableList<Int>) =
        FanCurveController(writeDuty = { writes += it }, readRpm = { 4800 }).apply {
            period = 50000
            curve = FanCurve(listOf(FanCurvePoint(30, 40), FanCurvePoint(90, 100)))
            slewPerSecond = 20 // 20%/s; with dt=1000ms below that's 20% per slew call
        }

    @Test fun `slew ramps toward the target gradually instead of jumping`() {
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.setTarget(80) // curve@80 = 90% (interp 40@30..100@90); applied starts at the 20% floor
        c.slew(1000)    // one 20% step: 20 -> 40, NOT a jump to 90
        assertEquals(40, c.appliedPercent)
        assertEquals(FanCurve.percentToDuty(40, 50000), w.last())
    }

    @Test fun `slew reaches the target after enough passes`() {
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.setTarget(80) // target 90%
        repeat(4) { c.slew(1000) } // 20 ->40 ->60 ->80 ->90
        assertEquals(90, c.appliedPercent)
    }

    @Test fun `slew writes while ramping then stops once it holds the target`() {
        // In manual mode the vendor never resets the duty, so re-writing the same value is needless (and it
        // corrupts the RPM tach). Write only on change: writes during the ramp, silent while holding.
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 100 // big step → reaches the target in one slew
        c.setTarget(50)       // curve@50 = 60%
        c.slew(1000)          // 20 -> 60: a write
        val afterReach = w.size
        c.slew(1000); c.slew(1000) // holding at 60 → no further writes
        assertEquals(afterReach, w.size)
        assertTrue("should write at least once while ramping", afterReach >= 1)
    }

    @Test fun `the first slew always writes, even when already sitting at the floor`() {
        val w = mutableListOf<Int>()
        val c = FanCurveController(writeDuty = { w += it }, readRpm = { null }).apply {
            period = 50000; slewPerSecond = 20
            curve = FanCurve(listOf(FanCurvePoint(30, 5))) // clamps to the 20% floor
        }
        c.setTarget(30)
        c.slew(1000)
        assertEquals(1, w.size)       // forced first write even though the applied % didn't move
        assertEquals(10000, w.last()) // 20% of 50000
    }

    @Test fun `thermal override snaps to full instantly even at a crawl slew rate`() {
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 1 // easing alone would crawl; the override must JUMP for safety
        c.setTarget(FanCurve.THERMAL_OVERRIDE_C)
        c.slew(300)
        assertEquals(100, c.appliedPercent)
        assertEquals(50000, w.last())
    }

    @Test fun `never writes below the 20 percent floor for a low curve`() {
        val w = mutableListOf<Int>()
        val c = FanCurveController(writeDuty = { w += it }, readRpm = { null }).apply {
            period = 50000; slewPerSecond = 20
            curve = FanCurve(listOf(FanCurvePoint(30, 5))) // below floor → clamps to 20%
        }
        c.setTarget(30)
        c.slew(1000)
        assertEquals(10000, w.last()) // 20% of 50000
    }

    @Test fun `a higher slew rate reaches the target in fewer passes`() {
        val slow = controller(mutableListOf()).apply { slewPerSecond = 5 }
        val fast = controller(mutableListOf()).apply { slewPerSecond = 30 }
        slow.setTarget(80); fast.setTarget(80) // target 90%
        slow.slew(1000); fast.slew(1000)
        assertTrue("fast (${fast.appliedPercent}) should lead slow (${slow.appliedPercent})",
            fast.appliedPercent > slow.appliedPercent)
    }

    @Test fun `reset returns the applied percent to the floor`() {
        val c = controller(mutableListOf())
        c.setTarget(80); repeat(4) { c.slew(1000) }
        c.reset()
        assertEquals(FanCurve.MIN_PERCENT, c.appliedPercent)
    }

    @Test fun `re-asserts the duty after something re-pins the node underneath us`() {
        // The Odin vendor fan daemon re-pins the duty node even in manual passthrough (observed live: PULSE
        // commanded 20% but the node read 50%). Write-on-change alone never corrects a value changed under us.
        // After reconcileActualDuty sees the node drift, the next slew must re-write PULSE's intended duty.
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 100
        c.setTarget(50)              // curve@50 = 60% → duty 30000
        c.slew(1000)                 // 20 -> 60: writes 30000
        c.slew(1000)                 // holding at 60 → no write (write-on-change)
        val beforeReassert = w.size
        assertTrue("a drift is reported", c.reconcileActualDuty(25000)) // node re-pinned to 50% (25000) ≠ 30000
        c.slew(1000)                 // must re-assert our 60%
        assertEquals("a re-pin forces exactly one re-assert write", beforeReassert + 1, w.size)
        assertEquals(FanCurve.percentToDuty(60, 50000), w.last())
    }

    @Test fun `does not re-assert when the live node still matches what we wrote`() {
        // RP6/Thor leave the duty alone — reconcile with the matching value must NOT force a needless write
        // (a blind re-write every tick is exactly the 20%<->vendor oscillation we must avoid).
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 100
        c.setTarget(50)              // 60% → duty 30000
        c.slew(1000)
        val held = w.size
        assertFalse("a match is not a drift", c.reconcileActualDuty(FanCurve.percentToDuty(60, 50000)))
        c.slew(1000)
        assertEquals("no drift → no extra write", held, w.size)
    }

    @Test fun `reassertCurrentDuty immediately re-writes the current applied duty without ramping`() {
        // When the vendor steals the node mid-cadence we re-pin our CURRENT value right away (not at the next
        // slow ramp tick) so the fan never audibly spins up. It writes now and does not advance the ramp.
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 100
        c.setTarget(50)          // 60% → 30000
        c.slew(1000)             // applied -> 60, writes 30000
        val held = w.size
        val appliedBefore = c.appliedPercent
        c.reconcileActualDuty(25000) // vendor stole the node
        c.reassertCurrentDuty()      // re-pin our 60% immediately
        assertEquals("re-pins exactly once", held + 1, w.size)
        assertEquals(30000, w.last())
        assertEquals("ramp position unchanged", appliedBefore, c.appliedPercent)
    }

    @Test fun `a null duty read never forces a re-assert`() {
        val w = mutableListOf<Int>()
        val c = controller(w)
        c.slewPerSecond = 100
        c.setTarget(50)
        c.slew(1000)
        val held = w.size
        c.reconcileActualDuty(null) // unreadable node → can't conclude drift, leave write-on-change alone
        c.slew(1000)
        assertEquals(held, w.size)
    }

    @Test fun `rpm reads back from the injected reader`() {
        assertEquals(4800, controller(mutableListOf()).rpm())
    }
}
