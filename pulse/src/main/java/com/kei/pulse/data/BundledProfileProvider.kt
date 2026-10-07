package com.kei.pulse.data

import android.content.Context
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.ProfileSource

private fun String.sanitizeAssetFileName(): String {
    return filter { character ->
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
    }
}

private fun assetProfileReader(context: Context): (String) -> String? {
    val appContext = context.applicationContext
    return { socModel ->
        val fileName = "bundled_profiles/${socModel.sanitizeAssetFileName()}.json"
        runCatching {
            appContext.assets.open(fileName).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}

class BundledProfileProvider(
    private val readProfileJson: (String) -> String?,
    private val parseProfiles: (String) -> List<PerformanceProfile> = ProfileJsonCodec::parseShareProfiles,
    private val socDetector: SocDetector = SocDetector(),
) {
    private var cachedSocModel: String? = null
    private var cachedProfiles: List<PerformanceProfile> = emptyList()
    constructor(
        context: Context,
        socDetector: SocDetector = SocDetector(),
    ) : this(
        readProfileJson = assetProfileReader(context),
        parseProfiles = ProfileJsonCodec::parseShareProfiles,
        socDetector = socDetector,
    )

    fun createProfiles(policies: List<CpuPolicyInfo>): List<PerformanceProfile> {
        val socModel = socDetector.detectSocModel() ?: return emptyList()
        val bundledProfiles = profilesForSoc(socModel)
        val policyIds = policies.associateBy { it.id }

        return bundledProfiles
            .mapIndexed { index, profile ->
                profile.copy(
                    source = ProfileSource.BUNDLED,
                    order = index,
                    isEditable = true,
                    isDeletable = true,
                )
            }
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() &&
                    profile.maxFrequencies.all { (policyId, frequency) ->
                        val policy = policyIds[policyId] ?: return@all false
                        frequency in policy.supportedFrequencies
                    }
            }
    }

    fun currentSocModel(): String? = socDetector.detectSocModel()

    private fun profilesForSoc(socModel: String): List<PerformanceProfile> {
        if (cachedSocModel == socModel) return cachedProfiles
        val profiles = readProfileJson(socModel)
            ?.let(parseProfiles)
            .orEmpty()
        cachedSocModel = socModel
        cachedProfiles = profiles
        return profiles
    }
}
