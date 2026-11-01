// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Charge limit + gaming bypass (F6, owner's choice D7), done with AYN's own "charging separation" switch
 * (system:is_charging_separation, verified: 1 = the Odin runs from the charger and the battery sits at 0 A).
 *
 * Charging speed (issue #3) is the one charge node HandyTuner writes: AYN's restrict_chg/restrict_cur pair, which
 * AYN's own Odin Settings writes for its charge switch. Measured on the Portal: restrict_chg=1 + restrict_cur=500000
 * took the battery from ~3.15 A to ~0.45 A in seconds, and the separation switch above doesn't touch the pair.
 * The pair goes back to [STOCK] at every boot. Limits over 1 A aren't honoured (2 A swung between 2.2 and -0.1 A), and
 * lifting any limit leaves the charger off fast charging (~0.8-1.4 A) until it is replugged: measured, not fixable here.
 */
object ChargeRule {
    /** limit 100 = no limit. [dockedLimit]: the limit while docked, null = the same as [limit]. */
    data class Settings(val limit: Int = 80, val gamingBypass: Boolean = true, val dockedLimit: Int? = null)

    /** Charging starts again this far under the limit, so it doesn't flick on and off around one percent. */
    const val HYSTERESIS = 5
    const val KEY = "is_charging_separation"

    /** The charging speed slider: 0.25-1 A (AYN's own top value), its top step meaning no limit. */
    val CURRENT_MA = 250..1250
    const val CURRENT_STEP = 250
    private const val CHG = "/sys/class/qcom-battery/restrict_chg"
    private const val CUR = "/sys/class/qcom-battery/restrict_cur"

    /** "restrict_chg restrict_cur" as the Odin boots: no restriction. */
    const val STOCK = "0 1000000"

    /**
     * Charging speed in mA, null = no limit. Its own file, not [file]: that one existing is what turns the charge
     * limit on, and setting a speed mustn't.
     */
    fun speedFile(ctx: Context) = File(ctx.filesDir, "charging_speed")
    fun speed(ctx: Context): Int? = runCatching { speedFile(ctx).readText().trim().toInt() }.getOrNull()
        ?.takeIf { it >= CURRENT_MA.first && it < CURRENT_MA.last }
    fun setSpeed(ctx: Context, ma: Int?) { if (ma == null) speedFile(ctx).delete() else speedFile(ctx).writeText(ma.toString()) }

    /** The pair for [currentMa], as [READ] returns it. */
    fun nodes(currentMa: Int?) = if (currentMa == null) STOCK else "1 ${currentMa * 1000}"

    /** One line for PServer (it garbles multi-line replies). */
    const val READ = "echo $(cat $CHG) $(cat $CUR)"

    /** Writes [nodes]; current first, so the restriction never turns on with the old current. */
    fun write(nodes: String) = nodes.split(' ').let { (chg, cur) -> "echo $cur > $CUR && echo $chg > $CHG" }

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
