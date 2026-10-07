package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * A small, self-calibrating power model for one SoC, learned while AutoTDP runs.
 *
 * Snapdragon exposes no power API, so the only draw signal is the battery's
 * `current_now × voltage_now` (valid on discharge). That's a *total system* draw — it can't be split
 * into CPU vs GPU in a single reading. The trick: **AutoTDP changes exactly one clock domain per
 * step**, so the draw delta measured across a single-domain step attributes cleanly to that domain.
 * Accumulating those deltas teaches this device's **CPU/GPU power split** — which then makes the
 * "EST PK" estimate and AutoTDP's trim choices reflect the real silicon instead of a fixed guess.
 *
 * Only the split is learned here (it's the robust, unbiased quantity from noisy single-draw readings);
 * the absolute scale stays the separately self-calibrated peak-draw figure (`learnedPeakW`). The model
 * is immutable + persisted per SoC, so it keeps improving and transfers across every app.
 */
@Serializable
data class PowerModel(
    val soc: String = "",
    /** Cumulative |Δwatts| attributed to CPU-only / GPU-only AutoTDP steps. */
    val cpuWattsSum: Float = 0f,
    val gpuWattsSum: Float = 0f,
    val cpuSamples: Int = 0,
    val gpuSamples: Int = 0,
) {
    val splitSamples: Int get() = cpuSamples + gpuSamples

    /** Learned fraction of dynamic power that's CPU (clamped so neither domain vanishes). */
    val cpuShare: Float
        get() = if (cpuWattsSum + gpuWattsSum > 0.01f) {
            (cpuWattsSum / (cpuWattsSum + gpuWattsSum)).coerceIn(0.15f, 0.85f)
        } else {
            DEFAULT_CPU_SHARE
        }

    val gpuShare: Float get() = 1f - cpuShare

    /** True once enough clean single-domain deltas have been seen to trust the split. */
    fun hasSplit(): Boolean = splitSamples >= MIN_SPLIT_SAMPLES && (cpuWattsSum + gpuWattsSum) > 0.5f

    /** Fold in one single-domain observation: [deltaW] = draw before the step − draw after. */
    fun observe(domain: Domain, deltaW: Float): PowerModel {
        // Ignore noise: a trim should lower draw by a small, plausible amount.
        if (deltaW <= NOISE_FLOOR_W || deltaW > MAX_STEP_W) return this
        return when (domain) {
            Domain.CPU -> copy(cpuWattsSum = cpuWattsSum + deltaW, cpuSamples = cpuSamples + 1)
            Domain.GPU -> copy(gpuWattsSum = gpuWattsSum + deltaW, gpuSamples = gpuSamples + 1)
        }
    }

    /**
     * 0..1 share of the full-power envelope the [caps] allow, using the **learned** CPU/GPU split
     * (vs. the fixed heuristic in [PowerEstimator]). Multiply by the nominal peak for absolute watts.
     */
    fun relativeIndex(policies: List<CpuPolicyInfo>, caps: Map<Int, Int>): Float? {
        val cpu = policies.filterNot { it.isGpu }
        if (cpu.isEmpty()) return null
        var num = 0.0
        var den = 0.0
        for (p in cpu) {
            val maxF = p.selectableMaxFreq.toDouble()
            if (maxF <= 0.0) continue
            val cap = (caps[p.id] ?: p.currentMaxFreq).toDouble().coerceIn(0.0, maxF)
            val w = p.cpuIds.size.coerceAtLeast(1) * (maxF / 1_000_000.0)
            num += w * (cap / maxF).pow(EXP)
            den += w
        }
        val cpuFrac = if (den > 0.0) num / den else 1.0
        val gpu = policies.firstOrNull { it.isGpu }
        val gpuFrac = gpu?.let { g ->
            val maxF = g.selectableMaxFreq.toDouble()
            if (maxF > 0.0) ((caps[g.id] ?: g.currentMaxFreq).toDouble().coerceIn(0.0, maxF) / maxF).pow(EXP) else cpuFrac
        } ?: cpuFrac
        return (cpuShare * cpuFrac + gpuShare * gpuFrac).toFloat()
    }

    enum class Domain { CPU, GPU }

    companion object {
        private const val EXP = 2.2 // power rises super-linearly with frequency (voltage scaling)
        const val DEFAULT_CPU_SHARE = 0.6f
        const val MIN_SPLIT_SAMPLES = 12
        private const val NOISE_FLOOR_W = 0.02f
        private const val MAX_STEP_W = 4f // a single 5% cap step can't plausibly move more than this
    }
}
