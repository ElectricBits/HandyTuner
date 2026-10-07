package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [CpuFloorController]'s prime-skip + cap-clamp discipline (Bug 1). The device
 * write itself goes through RootSupport (verified on hardware), but the two decisions that make the
 * floor cap-safe — which clusters are floorable, and what each floor target is — are pure and tested here.
 */
class CpuFloorControllerTest {

    // Odin 3: policy0 = perf (~3.53 GHz), policy6 = PRIME (~4.32 GHz, highest selectableMaxFreq).
    private val perf = CpuPolicyInfo(
        id = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        currentMaxFreq = 2_745_600,
        selectableMaxFreq = 3_532_800,
        observedMaxFreq = 3_532_800,
        minFreq = 998_400,
        supportedFrequencies = listOf(998_400, 1_843_200, 2_745_600, 3_532_800),
    )
    private val prime = CpuPolicyInfo(
        id = 6,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy6",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
        currentMaxFreq = 3_072_000,
        selectableMaxFreq = 4_320_000,
        observedMaxFreq = 4_320_000,
        minFreq = 1_075_200,
        supportedFrequencies = listOf(1_075_200, 3_072_000, 4_320_000),
    )

    @Test
    fun `prime cluster is never floored - its min is owned by the cap apply path`() {
        val floorable = CpuFloorController.floorableClusters(listOf(perf, prime))
        assertTrue(floorable.any { it.id == perf.id })
        assertFalse("prime must be skipped so its 444-locked min is not released", floorable.any { it.id == prime.id })
    }

    @Test
    fun `prime is identified by highest selectableMaxFreq regardless of id order`() {
        // Reversed order must still pick policy6 (highest freq) as prime, not whatever is last.
        val floorable = CpuFloorController.floorableClusters(listOf(prime, perf))
        assertEquals(listOf(perf.id), floorable.map { it.id })
    }

    @Test
    fun `floor target snaps to the nearest OPP when below the cap`() {
        // 60% of 3.53 GHz ≈ 2.12 GHz → nearest supported OPP is 1_843_200. Cap is wide open (full), so no clamp.
        val target = CpuFloorController.floorTargetFor(perf, percent = 60, currentCap = 3_532_800)
        assertEquals(1_843_200, target)
    }

    @Test
    fun `floor target is clamped to the current cap - a floor can never exceed the cluster cap`() {
        // 90% floor would snap to 3_532_800, but the cluster is capped at 2_745_600. Clamp down so the kernel
        // doesn't clamp the cap back UP to satisfy a min above it (the prime-cap-break class of bug).
        val target = CpuFloorController.floorTargetFor(perf, percent = 90, currentCap = 2_745_600)
        assertEquals(2_745_600, target)
        assertTrue("floor must never exceed the live cap", target <= 2_745_600)
    }

    @Test
    fun `floor target is left unclamped when the live cap is unreadable`() {
        val target = CpuFloorController.floorTargetFor(perf, percent = 90, currentCap = null)
        assertEquals(3_532_800, target)
    }
}
