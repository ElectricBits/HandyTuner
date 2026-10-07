package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo

/**
 * Detects the Adreno GPU and exposes it as a synthetic [CpuPolicyInfo]
 * (id = [CpuPolicyInfo.GPU_POLICY_ID]) so it tunes through the same pipeline as CPU
 * clusters.
 *
 * HOW ADRENO CAPPING ACTUALLY WORKS
 * ---------------------------------
 * Adreno GPUs are NOT capped by writing a frequency to devfreq/max_freq (the governor
 * ignores/resets it). They are capped by *power-level index* via `max_pwrlevel`:
 *
 *   - The OPP table (`gpu_available_frequencies`) is ordered fastest -> slowest.
 *   - Power level 0 = fastest, level (N-1) = slowest.
 *   - `max_pwrlevel` = the fastest level the GPU is ALLOWED to reach. Default 0 (uncapped).
 *     Raising it to e.g. 6 means "never run faster than level 6". The GPU still scales
 *     down below that, so we only cap the ceiling.
 *
 * So PULSE shows the user real MHz values (stored in kHz for unit-consistency with CPU),
 * and at the write boundary [com.kei.pulse.root.PerformanceCommandBuilder] converts the
 * chosen frequency into its power-level index and writes that integer to `max_pwrlevel`.
 *
 * Verified on Odin 3 (Adreno 830): 14 levels, 160-1100 MHz.
 */
class GpuFreqDetector(
    private val privilegedReader: PrivilegedSysfsReader,
) {
    fun detectAsPolicy(): CpuPolicyInfo? {
        val root = kgslRoot ?: return null
        val maxPwrlevelPath = "$root/max_pwrlevel"

        // OPP table (Hz). gpu_available_frequencies lives at the kgsl root; some kernels
        // only expose it under devfreq. Either way we sort ascending and work in kHz.
        val freqsKHz = parseHzToKHz(
            readText("$root/gpu_available_frequencies")
                ?: readText("$root/devfreq/available_frequencies"),
        )
        if (freqsKHz.size < 2) return null

        val topKHz = freqsKHz.last()
        val bottomKHz = freqsKHz.first()

        // Current ceiling = frequency at the current max_pwrlevel index (0 = uncapped/top).
        val currentLevel = readInt(maxPwrlevelPath) ?: 0
        val currentCapKHz = freqForLevel(freqsKHz, currentLevel)

        return CpuPolicyInfo(
            id = CpuPolicyInfo.GPU_POLICY_ID,
            policyPath = root,
            scalingMaxPath = maxPwrlevelPath,
            currentMaxFreq = currentCapKHz,
            selectableMaxFreq = topKHz,
            observedMaxFreq = topKHz,
            minFreq = bottomKHz,
            supportedFrequencies = freqsKHz, // ascending kHz, for the UI
            cpuIds = emptyList(),
        )
    }

    /** Reads the live GPU ceiling (kHz) by mapping the current power level back to a freq. */
    fun readCurrentMaxKHz(policy: CpuPolicyInfo): Int? {
        val level = readInt(policy.scalingMaxPath) ?: return null
        return freqForLevel(policy.supportedFrequencies, level)
    }

    private val kgslRoot: String?
        get() = listOf(
            "/sys/class/kgsl/kgsl-3d0",
            "/sys/devices/platform/soc@0/3d00000.gpu/kgsl/kgsl-3d0",
        ).firstOrNull { readText("$it/max_pwrlevel") != null }

    /**
     * Maps a power-level index to a frequency in kHz. [freqsKHz] is ascending, while power
     * levels are fastest-first, so level i corresponds to the (N-1-i)th ascending entry.
     */
    private fun freqForLevel(freqsKHz: List<Int>, level: Int): Int {
        if (freqsKHz.isEmpty()) return 0
        val idx = (freqsKHz.size - 1 - level).coerceIn(0, freqsKHz.lastIndex)
        return freqsKHz[idx]
    }

    internal fun parseHzToKHz(raw: String?): List<Int> =
        raw.orEmpty()
            .split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
            .map { (it / 1000L).toInt() }
            .filter { it > 0 }
            .distinct()
            .sorted()

    private fun readInt(path: String): Int? = readText(path)?.toIntOrNull()

    private fun readText(path: String): String? =
        privilegedReader.readText(path)?.trim()?.takeIf { it.isNotEmpty() }
}
