package com.kei.pulse.model

import kotlin.math.roundToInt

/**
 * Closed-loop temperature-target fan controller — the genuine control-theory approach to fan management.
 *
 * Instead of a static temp→% lookup (open-loop, can't adapt to ambient / load / dust / aging), a **PI
 * controller** drives the fan duty to hold the SoC at [targetTempC]:
 *
 *   duty = minSpin + Kp·e + Ki·∫e dt,   where e = currentTemp − target   (positive e = too hot → more fan)
 *
 * - **P** reacts immediately to how far over target we are; **I** trims out steady-state error (a persistent
 *   overshoot slowly pulls the fan up until temp settles on target). This *automatically* uses the minimum
 *   fan needed: silent at the floor when cool, ramping exactly as much as required when hot.
 * - **Anti-windup** (conditional integration): the integral only accumulates when the output isn't pinned at
 *   a rail in the same direction — so after a long pin at 100% it recovers to quiet instantly, no overshoot.
 * - **Hard safety:** at/above [FanCurve.THERMAL_OVERRIDE_C] it forces 100% and dumps the integral.
 *
 * Gains default to robust, conservative values (the SoC thermal plant is slow, and the service's duty-slew
 * further smooths the output) — tuned on real hardware, not by pumping heat into the device. Pure +
 * unit-tested; the service feeds it the live temp each tick and slews the fan toward the returned duty %.
 */
class FanTempController(private val minSpinPercent: Int = FanCurve.MIN_PERCENT) {
    var kp: Double = KP_DEFAULT
    var ki: Double = KI_DEFAULT

    private var integral: Double = 0.0

    /** Compute the fan duty % to hold [currentTempC] at [targetTempC]; [dtSec] is the time since the last call. */
    fun update(currentTempC: Int, targetTempC: Int, dtSec: Double): Int {
        if (currentTempC >= FanCurve.THERMAL_OVERRIDE_C) {
            integral = 0.0 // safety override: full fan, and don't carry stale integral out of the trip
            return 100
        }
        val error = (currentTempC - targetTempC).toDouble()
        val floor = minSpinPercent.toDouble()
        // Conditional integration (anti-windup): provisionally integrate, but only keep it if doing so doesn't
        // push further into a saturated rail.
        val candidateIntegral = integral + error * dtSec
        val provisional = floor + kp * error + ki * candidateIntegral
        val saturatingHigh = provisional > 100.0 && error > 0.0
        val saturatingLow = provisional < floor && error < 0.0
        if (!saturatingHigh && !saturatingLow) integral = candidateIntegral

        val out = floor + kp * error + ki * integral
        return out.roundToInt().coerceIn(minSpinPercent, 100)
    }

    /** Forget the integral state (e.g. a new session, or the fan was handed back to the vendor). */
    fun reset() {
        integral = 0.0
    }

    companion object {
        /** Proportional gain (% fan per °C over target): ~5°C over → +20% fan immediately. */
        const val KP_DEFAULT = 4.0

        /** Integral gain (% fan per °C·s): slowly trims out a persistent overshoot to settle on target. */
        const val KI_DEFAULT = 0.15

        /** Default target the controller holds the SoC at — cool enough to dodge throttling, still quiet. */
        const val DEFAULT_TARGET_C = 78

        /** User-selectable target-temp band (Cooler ⟷ Quieter): lower = cooler+louder, higher = warmer+quieter. */
        const val TARGET_MIN_C = 60
        const val TARGET_MAX_C = 88
    }
}
