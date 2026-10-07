// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.kei.pulse.control

import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.FanTempController
import com.kei.pulse.model.RgbMode

/**
 * The device-wide engine settings HandyTuner's Tuning page may change (merge-plan.md stage 4), one
 * whitelisted key at a time. Pure, so the rules are unit-tested: anything not listed here is refused.
 */
sealed interface EngineChange {
    data class FanTarget(val celsius: Int) : EngineChange
    data class Bias(val bias: AutoTdpBias) : EngineChange
    data class Sleep(val on: Boolean) : EngineChange
    data class Lights(val mode: RgbMode) : EngineChange
    /** Both sticks: an opaque RGB color and a brightness of 0..100 %. */
    data class LightColor(val argb: Int, val brightness: Int) : EngineChange
}

object EngineSettings {
    /**
     * 60..88 °C is the engine's own band; the top is also the owner's rule that the chip never runs near
     * 96 °C, so nothing above it is accepted even if the engine's band ever grows.
     */
    val FAN_RANGE = FanTempController.TARGET_MIN_C..minOf(FanTempController.TARGET_MAX_C, 88)

    fun parse(key: String, value: String): EngineChange? = when (key) {
        "fanTargetC" -> value.toIntOrNull()?.takeIf { it in FAN_RANGE }?.let { EngineChange.FanTarget(it) }
        "autoTdpBias" -> AutoTdpBias.entries.firstOrNull { it.name == value }?.let { EngineChange.Bias(it) }
        "sleep" -> when (value) { "on" -> EngineChange.Sleep(true); "off" -> EngineChange.Sleep(false); else -> null }
        "rgbMode" -> RgbMode.entries.firstOrNull { it.name == value }?.let { EngineChange.Lights(it) }
        // "RRGGBB,brightness%": exactly six hex digits, so a color can't smuggle in an alpha.
        "rgbColor" -> value.split(',').takeIf { it.size == 2 && Regex("[0-9A-Fa-f]{6}").matches(it[0]) }?.let { (hex, b) ->
            b.toIntOrNull()?.takeIf { it in 0..100 }?.let { EngineChange.LightColor(0xFF000000.toInt() or hex.toInt(16), it) }
        }
        else -> null
    }
}
