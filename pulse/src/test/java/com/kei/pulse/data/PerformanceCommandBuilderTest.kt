package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.PerformanceCommandBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceCommandBuilderTest {

    private val builder = PerformanceCommandBuilder()
    private val policies = listOf(
        CpuPolicyInfo(
            id = 0,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            currentMaxFreq = 2_745_600,
            selectableMaxFreq = 3_532_800,
            observedMaxFreq = 3_532_800,
            minFreq = 998_400,
            supportedFrequencies = listOf(998_400, 2_745_600, 3_532_800),
        ),
        CpuPolicyInfo(
            id = 6,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy6",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            currentMaxFreq = 3_072_000,
            selectableMaxFreq = 4_320_000,
            observedMaxFreq = 4_320_000,
            minFreq = 1_075_200,
            supportedFrequencies = listOf(1_075_200, 3_072_000, 4_320_000),
        ),
    )

    @Test
    fun `builds underclock script without service stop`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        assertTrue(!script.contains("stop "))
        assertTrue(script.contains("echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
    }

    @Test
    fun `lowerMinPolicyIds drops only the named cluster's scaling_min, before the cap, locked read-only`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 1_075_200),
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime only
        )
        // Prime (policy6): min dropped to its floor and locked so a higher cap can't be clamped back.
        assertTrue(script.contains("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        // The min write precedes the max write (kernel enforces max >= min).
        assertTrue(
            script.indexOf("policy6/scaling_min_freq") < script.indexOf("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
        )
        // The perf cluster (policy0) is NOT touched — that's what keeps its cap biting.
        assertTrue(!script.contains("policy0/scaling_min_freq"))
    }

    @Test
    fun `default leaves scaling_min_freq untouched`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )
        assertTrue(!script.contains("scaling_min_freq"))
    }

    @Test
    fun `builds reset script without service restart`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 3_532_800, 6 to 4_320_000),
            isReset = true,
        )

        assertTrue(script.contains("chmod 644 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(!script.contains("start "))
        assertTrue(!script.contains("stop perfd"))
    }

    @Test
    fun `prime boost-limit routing lowers and locks the prime min before its no-turbo max, perf untouched`() {
        // Bug 2: setPrimeCoreBoostLimited / reapplyCustomSideControls now route the no-turbo cap through
        // applyFreqsToDevice(policies, mapOf(primeId to target)) — modelled here as the FULL policy set with
        // only the prime (policy6) in the freq map and the prime id as the lowerMin target. The no-turbo
        // target is the second-highest OPP (4_320_000 → 3_072_000).
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(6 to 3_072_000), // only the prime is written (the boost limit)
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime id, derived from the full set by highest selectableMaxFreq
        )
        // The prime min is dropped to its floor and 444-locked so a sub-min target could never be rejected.
        assertTrue(script.contains("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        // …before the no-turbo max, which is also locked read-only.
        assertTrue(
            script.indexOf("policy6/scaling_min_freq") <
                script.indexOf("echo 3072000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
        )
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
        // The perf cluster is never touched by a prime boost limit (no min churn, no max stomp).
        assertTrue(!script.contains("policy0/scaling_min_freq"))
        assertTrue(!script.contains("policy0/scaling_max_freq"))
    }

    @Test
    fun `holding a partial cap set leaves an uncapped prime fully untouched even when it is the lowerMin target`() {
        // Bug 9 re-assert: the FULL policy set is passed (so the prime is identified correctly), but only the
        // clusters the binding actually capped are in the freq map. Here the perf cluster (policy0) is held and
        // the prime (policy6) is absent (uncapped).
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600), // only the perf cluster is held
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime id derived from the full set
        )
        // The perf cap is re-asserted and locked read-only.
        assertTrue(script.contains("echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        // The uncapped prime is NOT written at all — neither its min (despite being the lowerMin target) nor its
        // max — because it has no entry in the freq map. This is what makes re-asserting a partial cap set safe.
        assertTrue(!script.contains("policy6/scaling_min_freq"))
        assertTrue(!script.contains("policy6/scaling_max_freq"))
    }
}
