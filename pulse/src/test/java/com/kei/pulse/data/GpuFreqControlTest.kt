package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.PerformanceCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFreqControlTest {

    private class FakeReader(private val files: Map<String, String>) : PrivilegedSysfsReader {
        override fun readText(path: String): String? = files[path]
    }

    private val root = "/sys/class/kgsl/kgsl-3d0"

    // Real Adreno 830 (Odin 3) table, Hz, fastest-first as the kernel reports it.
    private val odin3FreqsHz =
        "1100000000 1050000000 967000000 900000000 832000000 734000000 660000000 " +
            "607000000 525000000 443000000 389000000 342000000 222000000 160000000"

    private fun reader(maxPwrlevel: String) = FakeReader(
        mapOf(
            "$root/gpu_available_frequencies" to odin3FreqsHz,
            "$root/max_pwrlevel" to maxPwrlevel,
        ),
    )

    @Test
    fun `detects gpu as synthetic policy pointing at max_pwrlevel`() {
        val policy = GpuFreqDetector(reader("0")).detectAsPolicy()!!

        assertEquals(CpuPolicyInfo.GPU_POLICY_ID, policy.id)
        assertTrue(policy.isGpu)
        assertEquals("$root/max_pwrlevel", policy.scalingMaxPath)
        // 14 OPPs, ascending kHz
        assertEquals(14, policy.supportedFrequencies.size)
        assertEquals(160_000, policy.supportedFrequencies.first())
        assertEquals(1_100_000, policy.supportedFrequencies.last())
        // level 0 = uncapped = top frequency
        assertEquals(1_100_000, policy.currentMaxFreq)
        assertEquals(1_100_000, policy.selectableMaxFreq)
        assertEquals(160_000, policy.minFreq)
        assertTrue(policy.cpuIds.isEmpty())
    }

    @Test
    fun `returns null when no kgsl node is present`() {
        assertNull(GpuFreqDetector(FakeReader(emptyMap())).detectAsPolicy())
    }

    @Test
    fun `maps current power level back to a frequency`() {
        val detector = GpuFreqDetector(reader("6")) // level 6 = 660 MHz
        val policy = detector.detectAsPolicy()!!
        assertEquals(660_000, policy.currentMaxFreq)
        assertEquals(660_000, detector.readCurrentMaxKHz(policy))
    }

    @Test
    fun `command builder writes the power level index for a chosen frequency`() {
        val gpu = GpuFreqDetector(reader("0")).detectAsPolicy()!!
        val script = PerformanceCommandBuilder().buildApplyScript(
            policies = listOf(gpu),
            selectedValues = mapOf(CpuPolicyInfo.GPU_POLICY_ID to 660_000), // 660 MHz
            isReset = false,
        )
        // 660 MHz is level 6 in this 14-step table; min_pwrlevel widened to slowest (13)
        assertTrue(script.contains("echo 13 > $root/min_pwrlevel"))
        assertTrue(script.contains("echo 6 > $root/max_pwrlevel"))
        assertTrue(script.contains("chmod 444 $root/max_pwrlevel"))
    }

    @Test
    fun `selecting the top frequency uncaps the gpu (level 0)`() {
        val gpu = GpuFreqDetector(reader("0")).detectAsPolicy()!!
        val script = PerformanceCommandBuilder().buildApplyScript(
            policies = listOf(gpu),
            selectedValues = mapOf(CpuPolicyInfo.GPU_POLICY_ID to 1_100_000),
            isReset = true,
        )
        assertTrue(script.contains("echo 0 > $root/max_pwrlevel"))
        assertTrue(script.contains("echo 13 > $root/min_pwrlevel"))
        // GPU bounds are always locked read-only (444), even on the Max/reset tier, so the
        // vendor performance daemon can't stomp min_pwrlevel and floor the GPU mid-range.
        assertTrue(script.contains("chmod 444 $root/max_pwrlevel"))
    }
}
