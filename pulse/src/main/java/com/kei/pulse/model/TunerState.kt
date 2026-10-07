package com.kei.pulse.model

data class TunerState(
    val isLoading: Boolean = true,
    val isPServerAvailable: Boolean = false,
    val policies: List<CpuPolicyInfo> = emptyList(),
    val actualValues: Map<Int, Int> = emptyMap(),
    val currentValues: Map<Int, Int> = emptyMap(),
    val bundledProfiles: List<PerformanceProfile> = emptyList(),
    val userProfiles: List<PerformanceProfile> = emptyList(),
    val displayProfiles: List<PerformanceProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedDisplayProfileId: String? = null,
    val selectedDisplayProfileName: String? = null,
    val activeDisplayProfileId: String? = null,
    val activeDisplayProfileName: String? = null,
    val lastAppliedDisplayProfileId: String? = null,
    val isManualSelection: Boolean = false,
    val isManualActive: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)
