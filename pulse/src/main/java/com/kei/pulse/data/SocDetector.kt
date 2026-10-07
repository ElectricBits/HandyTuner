package com.kei.pulse.data

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

open class SocDetector {

    companion object {
        @Volatile
        private var processCachedSocModel: String? = null

        // Friendly marketing names for the SoCs PULSE targets; raw codename is the fallback.
        private val FRIENDLY_NAMES = mapOf(
            "CQ8725S" to "Dragonwing Q8", // AYN Odin 3
            "QCS8550" to "SD 8 Gen 2",    // Retroid Pocket 6 / AYN Thor
        )

        /** A display-friendly chip name for [raw], or [raw] itself (trimmed) when unmapped. */
        fun displayName(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return FRIENDLY_NAMES[trimmed.uppercase()] ?: trimmed
        }
    }

    open fun detectSocModel(): String? {
        processCachedSocModel?.let { return it }
        val candidates = listOf(
            readProperty("ro.soc.model"),
            readProperty("ro.vendor.qti.soc_model"),
            readProperty("ro.fota.platform"),
            Build.SOC_MODEL,
            Build.HARDWARE,
            Build.BOARD,
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.also { processCachedSocModel = it }
    }

    private fun readProperty(name: String): String? {
        return runCatching {
            val process = ProcessBuilder("getprop", name)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output.trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
