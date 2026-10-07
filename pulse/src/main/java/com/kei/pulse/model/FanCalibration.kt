package com.kei.pulse.model

import kotlin.math.roundToInt

/** One reading from an autocalibrate sweep: at [percent] duty the fan tach read [rpm]. */
data class FanCalibrationSample(val percent: Int, val rpm: Int)

/**
 * Result of an EVGA-style fan autocalibrate. The editor sweeps the duty from low→100% (settling at each
 * step) and records the RPM tach; [fromSweep] reduces those samples to:
 *  - [minSpinPercent] — the lowest duty that actually moves air on THIS unit (below it the fan is stalled),
 *    never below the vendor-safe [FanCurve.MIN_PERCENT] floor, and
 *  - [recommendedCurve] — a sensible temp→% curve anchored to that min-spin (cool) and ramping to 100% at
 *    the thermal trip.
 * Pure so it's unit-tested; the live sweep (writing duty / reading the tach) is the caller's job.
 */
data class FanCalibration(
    val minSpinPercent: Int,
    val maxRpm: Int,
    val comfortPercent: Int,
    val recommendedCurve: FanCurve,
) {
    companion object {
        /** RPM must reach this fraction of the sweep's max to count the fan as "actually spinning". */
        const val RISE_FRACTION = 0.15f

        /** A "comfortable working RPM" — quiet-but-cooling — is this fraction of the fan's max. The curve holds
         *  at/below the duty that hits it through normal temps, only ramping past it toward full when genuinely
         *  hot. (≈0.6 of the Odin's 14700 RPM max ≈ ~50% duty, near the user's stated comfort level.) */
        const val COMFORT_RPM_FRACTION = 0.6

        /** The curve's quiet/min-spin anchor sits at the measured idle temp, clamped to this sane band. */
        const val IDLE_ANCHOR_MIN = 35
        const val IDLE_ANCHOR_MAX = 55
        private const val DEFAULT_IDLE_ANCHOR = 40
        private const val HOT_KNEE_C = 80 // reach the comfort level by here; ramp toward full above it

        /**
         * Reduce a duty→RPM sweep (and the measured [idleTempC]) to this unit's own calibration:
         *  - [minSpinPercent]: lowest duty that actually moves air,
         *  - [comfortPercent]: the duty at a comfortable working RPM (quiet-but-cooling), and
         *  - [recommendedCurve]: a CONVEX, audibility-balanced curve — quiet at idle, staying at/below comfort
         *    through normal temps, then ramping hard to full only as it nears the 90°C trip — anchored to the
         *    device's real idle temp. Different fans (different RPM ranges) yield different curves (it adapts).
         */
        fun fromSweep(samples: List<FanCalibrationSample>, idleTempC: Int? = null): FanCalibration {
            val sorted = samples.sortedBy { it.percent }
            val maxRpm = sorted.maxOfOrNull { it.rpm } ?: 0
            // No samples, or a tach we can't read (all-zero RPM) → can't characterize; hand back the safe default.
            if (sorted.isEmpty() || maxRpm <= 0) {
                return FanCalibration(FanCurve.MIN_PERCENT, maxRpm.coerceAtLeast(0), 50, FanCurve.DEFAULT)
            }
            val minSpin = (sorted.firstOrNull { it.rpm >= RISE_FRACTION * maxRpm }?.percent ?: sorted.last().percent)
                .coerceIn(FanCurve.MIN_PERCENT, 100)
            val comfort = (sorted.firstOrNull { it.rpm >= COMFORT_RPM_FRACTION * maxRpm }?.percent ?: sorted.last().percent)
                .coerceIn(minSpin, 100)
            val idle = (idleTempC ?: DEFAULT_IDLE_ANCHOR).coerceIn(IDLE_ANCHOR_MIN, IDLE_ANCHOR_MAX)
            return FanCalibration(minSpin, maxRpm, comfort, recommendedCurve(minSpin, comfort, idle))
        }

        /**
         * Convex, audibility-balanced curve: quiet ([minSpin]) at idle, easing gently so normal temps stay
         * at/below [comfort] (the quiet-but-cooling level), then ramping hard to full as it nears the trip.
         */
        private fun recommendedCurve(minSpin: Int, comfort: Int, idleAnchor: Int): FanCurve {
            val trip = FanCurve.THERMAL_OVERRIDE_C
            val span = (HOT_KNEE_C - idleAnchor).coerceAtLeast(1)
            fun easedPct(frac: Double) =
                (minSpin + (comfort - minSpin) * frac).roundToInt().coerceIn(FanCurve.MIN_PERCENT, 100)
            return FanCurve(
                listOf(
                    FanCurvePoint(idleAnchor, minSpin),                                  // quiet at idle
                    FanCurvePoint((idleAnchor + span * 0.45).roundToInt(), easedPct(0.30)), // still quiet
                    FanCurvePoint((idleAnchor + span * 0.75).roundToInt(), easedPct(0.62)), // easing up
                    FanCurvePoint(HOT_KNEE_C, comfort),                                  // comfort working max
                    FanCurvePoint((HOT_KNEE_C + trip) / 2, (comfort + 100) / 2),         // ramping for cooling
                    FanCurvePoint(trip, 100),                                            // full at the trip
                ),
            )
        }
    }
}
