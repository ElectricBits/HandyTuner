// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

/**
 * What is holding the game back, in plain words. First match wins, most
 * urgent first: memory and heat are problems whatever the load is.
 *
 * ponytail: fixed thresholds from what was measured on the Odin 2 Portal;
 * tune them here if the line blames the wrong thing on other games.
 */
/**
 * Frame rates that mean "holding at a limit the user asked for" rather than "losing frames":
 * every cap and AutoTDP target HandyTuner can request, plus the panel's own rate. Derived rather
 * than typed out because a hand-written list had already fallen behind — it read 30/60/120 and
 * missed 40, so a game capped at 40 was reported as "No bottleneck" (docs/BUGS.md #9). 0 is
 * excluded: it is "no cap", not a rate to be near.
 */
private val HELD = (PulsePart.CAPS + PulsePart.AUTO_FPS + 120 - 0).sorted()

object Bottleneck {
    data class Reading(
        val fps: Int?, val gpuBusy: Int?, val busiestCore: Int?, val cpuTempC: Int?, val memPressure: Double?,
    )

    enum class Verdict(val text: String) {
        MEMORY("Low memory — Android is short on RAM"),
        HEAT("Heat — chip is throttling"),
        HOT_OK("Warm — holding cap"),
        GPU("GPU-limited — graphics chip is maxed out"),
        CPU("CPU-limited — one core is maxed out"),
        CAP("Frame cap — running at its limit, all good"),
        NONE("No bottleneck"),
    }

    fun of(r: Reading): Verdict {
        val capped = r.fps != null && HELD.any { kotlin.math.abs(r.fps - it) <= 2 }
        return when {
        (r.memPressure ?: 0.0) >= 10.0 -> Verdict.MEMORY      // /proc/pressure/memory "some avg10", %
        // Near the 90 °C guard line. Only a bottleneck once frames drop: hot at a steady cap is a warning.
        (r.cpuTempC ?: 0) >= 88 -> if (capped) Verdict.HOT_OK else Verdict.HEAT
        (r.gpuBusy ?: 0) >= 90 -> Verdict.GPU
        (r.busiestCore ?: 0) >= 90 -> Verdict.CPU               // games stall on one thread, not the average
        capped -> Verdict.CAP
        else -> Verdict.NONE
        }
    }
}
