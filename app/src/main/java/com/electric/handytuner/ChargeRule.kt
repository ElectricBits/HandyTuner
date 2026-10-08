// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Charge limit + gaming bypass (F6, owner's choice D7), done with AYN's own "charging separation" switch
 * (system:is_charging_separation, verified: 1 = the Odin runs from the charger and the battery sits at 0 A).
 * HandyTuner never writes the charge nodes itself (D4).
 */
object ChargeRule {
    /** limit 100 = no limit. [dockedLimit]: the limit while docked, null = the same as [limit]. */
    data class Settings(val limit: Int = 80, val gamingBypass: Boolean = true, val dockedLimit: Int? = null)

    /** Charging starts again this far under the limit, so it doesn't flick on and off around one percent. */
    const val HYSTERESIS = 5
    const val KEY = "is_charging_separation"

    fun file(ctx: Context) = File(ctx.filesDir, "charging.properties")

    fun load(ctx: Context): Settings = runCatching {
        val p = Properties().apply { file(ctx).inputStream().use { load(it) } }
        Settings(p.getProperty("limit")?.toIntOrNull()?.coerceIn(50, 100) ?: 80, p.getProperty("bypass")?.toBoolean() ?: true,
            p.getProperty("dockedLimit")?.toIntOrNull()?.coerceIn(50, 100))
    }.getOrDefault(Settings())

    fun save(ctx: Context, s: Settings) = file(ctx).storeAtomic(Properties().apply {
        setProperty("limit", s.limit.coerceIn(50, 100).toString()); setProperty("bypass", s.gamingBypass.toString())
        s.dockedLimit?.let { setProperty("dockedLimit", it.coerceIn(50, 100).toString()) }
    })

    /**
     * Should the battery be bypassed now? null = leave the switch as it is (inside the hysteresis band, or
     * unplugged, where it makes no difference).
     */
    fun decide(s: Settings, plugged: Boolean, level: Int, gaming: Boolean, bypassedNow: Boolean, docked: Boolean = false): Boolean? {
        if (!plugged) return null
        if (gaming && s.gamingBypass) return true
        val limit = if (docked) s.dockedLimit ?: s.limit else s.limit
        if (limit >= 100) return false
        return when {
            level >= limit -> true
            level <= limit - HYSTERESIS -> false
            else -> bypassedNow.takeIf { it }           // in the band: keep bypassing if it already was, else keep charging
                ?: false
        }
    }
}
