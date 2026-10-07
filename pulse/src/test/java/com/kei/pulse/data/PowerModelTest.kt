package com.kei.pulse.data

import com.kei.pulse.data.PowerModel.Domain
import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-math coverage for the learned per-SoC power model. */
class PowerModelTest {

    private fun cpu(id: Int, maxKhz: Int) = CpuPolicyInfo(
        id = id,
        policyPath = "/p$id",
        scalingMaxPath = "/p$id/max",
        currentMaxFreq = maxKhz,
        selectableMaxFreq = maxKhz,
        observedMaxFreq = maxKhz,
        minFreq = maxKhz / 4,
        supportedFrequencies = listOf(maxKhz / 2, maxKhz),
        cpuIds = listOf(id),
    )

    private fun gpu(maxKhz: Int) = cpu(CpuPolicyInfo.GPU_POLICY_ID, maxKhz)

    @Test
    fun shareShiftsTowardTheDomainThatCostsMorePower() {
        var m = PowerModel(soc = "TEST")
        repeat(8) { m = m.observe(Domain.CPU, 0.3f) }
        repeat(8) { m = m.observe(Domain.GPU, 0.9f) }
        assertTrue("GPU drew more per step ⇒ higher GPU share", m.gpuShare > m.cpuShare)
        assertEquals(16, m.splitSamples)
    }

    @Test
    fun ignoresNoiseSpikesAndZeroDeltas() {
        var m = PowerModel(soc = "TEST")
        m = m.observe(Domain.CPU, 0f)      // no change
        m = m.observe(Domain.CPU, -1f)     // draw went up (load shift) — ignore
        m = m.observe(Domain.CPU, 99f)     // implausible spike — ignore
        assertEquals(0, m.splitSamples)
    }

    @Test
    fun splitIsUntrustedUntilEnoughSamples() {
        var m = PowerModel(soc = "TEST")
        assertFalse(m.hasSplit())
        repeat(PowerModel.MIN_SPLIT_SAMPLES) { m = m.observe(Domain.CPU, 0.5f) }
        assertTrue(m.hasSplit())
    }

    @Test
    fun relativeIndexIsOneAtFullCapsAndLowerWhenCapped() {
        val m = PowerModel(soc = "TEST")
        val policies = listOf(cpu(0, 2_000_000), gpu(1_000_000))
        val full = mapOf(0 to 2_000_000, CpuPolicyInfo.GPU_POLICY_ID to 1_000_000)
        assertEquals(1.0f, m.relativeIndex(policies, full)!!, 0.001f)

        val capped = mapOf(0 to 1_000_000, CpuPolicyInfo.GPU_POLICY_ID to 500_000)
        assertTrue(m.relativeIndex(policies, capped)!! < 1.0f)
    }

    @Test
    fun relativeIndexNullWithoutCpuPolicies() {
        assertNull(PowerModel().relativeIndex(emptyList(), emptyMap()))
    }
}
