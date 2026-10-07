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
object Bottleneck {
    data class Reading(
        val fps: Int?, val gpuBusy: Int?, val busiestCore: Int?, val cpuTempC: Int?, val memPressure: Double?,
        /** The kernel is limiting the chip for heat right now ([Stats.throttling]); null = can't tell. */
        val throttling: Boolean? = null,
        /** The cap or Auto target this game really has (its saved settings); null = none, the game runs free. */
        val heldFps: Int? = null,
        /** [heldFps] is an AutoTDP target rather than a fixed cap. */
        val heldAuto: Boolean = false,
        /** The rate the last few readings all sat at ([Stats.steadyAt]); null = not steady. */
        val steadyFps: Int? = null,
    )

    /** Plain, calm words (owner, 2026-10-07: accurate and not scary). [warn] = amber; everything else is gray. */
    enum class Verdict(val warn: Boolean = false) {
        MEMORY(warn = true), HEAT(warn = true), HOT_OK(warn = true), CAP, STEADY, GPU, CPU, NONE;

        fun text(r: Reading): String = when (this) {
            MEMORY -> "Memory is running low"
            HEAT -> "The chip is slowing down to cool off"
            HOT_OK -> "Cooling off, still holding ${r.heldFps} fps"
            GPU -> "The graphics chip is the limit right now"
            CPU -> "One CPU core is the limit right now"
            CAP -> if (r.heldAuto) "Auto is holding ${r.heldFps} fps" else "Holding your ${r.heldFps} fps cap"
            STEADY -> "Steady at ${r.steadyFps} fps"
            NONE -> "Running smoothly"
        }
    }

    fun of(r: Reading): Verdict {
        // Only a cap this game really has: a game that runs at 60 on its own isn't "at a cap" (it said so on Max).
        val capped = r.fps != null && r.heldFps != null && kotlin.math.abs(r.fps - r.heldFps) <= 2
        return when {
            (r.memPressure ?: 0.0) >= 10.0 -> Verdict.MEMORY      // /proc/pressure/memory "some avg10", %
            // Only when the kernel really is slowing the chip for heat (owner, 2026-10-07); at a steady cap it's a warning.
            r.throttling == true -> if (capped) Verdict.HOT_OK else Verdict.HEAT
            capped -> Verdict.CAP
            // A game sitting at a rate it chose (its own frame limit): a busy core or GPU isn't what's holding it back.
            r.heldFps == null && r.steadyFps != null -> Verdict.STEADY
            (r.gpuBusy ?: 0) >= 90 -> Verdict.GPU
            (r.busiestCore ?: 0) >= 90 -> Verdict.CPU               // games stall on one thread, not the average
            else -> Verdict.NONE
        }
    }
}
