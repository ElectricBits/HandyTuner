package com.kei.pulse.model

data class CpuPolicyInfo(
    val id: Int,
    val policyPath: String,
    val scalingMaxPath: String,
    val currentMaxFreq: Int,
    val selectableMaxFreq: Int,
    val observedMaxFreq: Int,
    val minFreq: Int,
    val supportedFrequencies: List<Int>,
    val cpuIds: List<Int> = listOf(id),
) {
    /** True when this synthetic policy represents the Adreno GPU rather than a CPU cluster. */
    val isGpu: Boolean get() = id == GPU_POLICY_ID

    companion object {
        /**
         * Reserved synthetic policy id used to represent the Adreno GPU as a tunable
         * "cluster" so it flows through the same profile / apply / verify pipeline as
         * CPU clusters. Negative so it never collides with a real cpufreq policyN.
         */
        const val GPU_POLICY_ID = -100
    }
}
