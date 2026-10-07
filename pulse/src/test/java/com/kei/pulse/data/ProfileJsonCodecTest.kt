package com.kei.pulse.data

import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.ProfileSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonCodecTest {

    @Test
    fun `share export omits app internal profile fields`() {
        val json = ProfileJsonCodec.encodeShareFile(
            profiles = listOf(
                PerformanceProfile(
                    id = "bundled_cq8725s_small",
                    name = "Small Underclock",
                    maxFrequencies = mapOf(0 to 2_745_600, 6 to 3_072_000),
                    source = ProfileSource.BUNDLED,
                    order = 7,
                    isEditable = false,
                    isDeletable = false,
                ),
            ),
            socModel = "CQ8725S",
        )

        assertTrue(json.contains("\"profiles\""))
        assertTrue(json.contains("\"id\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"maxFrequencies\""))
        assertFalse(json.contains("\"source\""))
        assertFalse(json.contains("\"isEditable\""))
        assertFalse(json.contains("\"isDeletable\""))
        assertFalse(json.contains("\"order\""))
    }

    @Test
    fun `share import parses public profile schema with array order`() {
        val profiles = ProfileJsonCodec.parseShareProfiles(
            """
                {
                  "schemaVersion": 1,
                  "socModel": "CQ8725S",
                  "profiles": [
                    {
                      "id": "bundled_cq8725s_small",
                      "name": "Small Underclock",
                      "maxFrequencies": {
                        "0": 2745600,
                        "6": 3072000
                      }
                    },
                    {
                      "id": "custom",
                      "name": "Custom",
                      "maxFrequencies": {
                        "0": 2227200
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("bundled_cq8725s_small", "custom"), profiles.map { it.id })
        assertEquals(listOf(0, 1), profiles.map { it.order })
        assertEquals(mapOf(0 to 2_745_600, 6 to 3_072_000), profiles.first().maxFrequencies)
        assertEquals(ProfileSource.USER, profiles.first().source)
    }
}
