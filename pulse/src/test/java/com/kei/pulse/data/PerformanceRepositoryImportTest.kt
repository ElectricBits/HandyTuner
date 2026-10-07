package com.kei.pulse.data

import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRepositoryImportTest {

    @Test
    fun `imported bundled profile overrides bundled profile and marks it for restoration`() {
        val merge = mergeImportedProfiles(
            currentProfiles = listOf(
                profile(
                    id = "bundled_small",
                    name = "Small",
                    source = ProfileSource.BUNDLED,
                    order = 0,
                    frequencies = mapOf(0 to 2_000),
                ),
            ),
            defaultBundledProfiles = listOf(
                profile(
                    id = "bundled_small",
                    name = "Default Small",
                    source = ProfileSource.BUNDLED,
                    order = 0,
                    frequencies = mapOf(0 to 2_000),
                ),
            ),
            importedProfiles = listOf(
                profile(
                    id = "bundled_small",
                    name = "Imported Small",
                    source = ProfileSource.USER,
                    order = 0,
                    frequencies = mapOf(0 to 1_500),
                ),
            ),
        )

        assertEquals(setOf("bundled_small"), merge.restoredBundledProfileIds)
        assertEquals(
            profile(
                id = "bundled_small",
                name = "Imported Small",
                source = ProfileSource.BUNDLED,
                order = 0,
                frequencies = mapOf(0 to 1_500),
            ),
            merge.profiles.single(),
        )
    }

    @Test
    fun `imported bundled profile restores a deleted bundled profile`() {
        val merge = mergeImportedProfiles(
            currentProfiles = emptyList(),
            defaultBundledProfiles = listOf(
                profile(
                    id = "bundled_small",
                    name = "Default Small",
                    source = ProfileSource.BUNDLED,
                    order = 2,
                    frequencies = mapOf(0 to 2_000),
                ),
            ),
            importedProfiles = listOf(
                profile(
                    id = "bundled_small",
                    name = "Imported Small",
                    source = ProfileSource.USER,
                    order = 0,
                    frequencies = mapOf(0 to 1_500),
                ),
            ),
        )

        assertEquals(setOf("bundled_small"), merge.restoredBundledProfileIds)
        assertEquals("bundled_small", merge.profiles.single().id)
        assertEquals(ProfileSource.BUNDLED, merge.profiles.single().source)
        assertEquals(2, merge.profiles.single().order)
    }

    @Test
    fun `imported user profile overrides existing user profile with matching id`() {
        val merge = mergeImportedProfiles(
            currentProfiles = listOf(
                profile(
                    id = "custom",
                    name = "Custom",
                    source = ProfileSource.USER,
                    order = 3,
                    frequencies = mapOf(0 to 2_000),
                ),
            ),
            defaultBundledProfiles = emptyList(),
            importedProfiles = listOf(
                profile(
                    id = "custom",
                    name = "Imported Custom",
                    source = ProfileSource.USER,
                    order = 0,
                    frequencies = mapOf(0 to 1_500),
                ),
            ),
        )

        assertEquals(emptySet<String>(), merge.restoredBundledProfileIds)
        assertEquals(
            profile(
                id = "custom",
                name = "Imported Custom",
                source = ProfileSource.USER,
                order = 3,
                frequencies = mapOf(0 to 1_500),
            ),
            merge.profiles.single(),
        )
    }

    @Test
    fun `imported new profile preserves id and appends after current profiles`() {
        val merge = mergeImportedProfiles(
            currentProfiles = listOf(
                profile(
                    id = "existing",
                    name = "Existing",
                    source = ProfileSource.USER,
                    order = 0,
                    frequencies = mapOf(0 to 2_000),
                ),
            ),
            defaultBundledProfiles = emptyList(),
            importedProfiles = listOf(
                profile(
                    id = "imported",
                    name = "Imported",
                    source = ProfileSource.USER,
                    order = 0,
                    frequencies = mapOf(0 to 1_500),
                ),
            ),
        )

        assertEquals(
            profile(
                id = "imported",
                name = "Imported",
                source = ProfileSource.USER,
                order = 1,
                frequencies = mapOf(0 to 1_500),
            ),
            merge.profiles.single(),
        )
    }

    private fun profile(
        id: String,
        name: String,
        source: ProfileSource,
        order: Int,
        frequencies: Map<Int, Int>,
    ) = PerformanceProfile(
        id = id,
        name = name,
        maxFrequencies = frequencies,
        source = source,
        order = order,
        isEditable = true,
        isDeletable = true,
    )
}
