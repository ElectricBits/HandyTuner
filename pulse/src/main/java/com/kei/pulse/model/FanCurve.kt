package com.kei.pulse.model

import kotlin.math.roundToInt

/** One control point on the fan curve: at [tempC]°C, run the fan at [percent]% of max duty. */
data class FanCurvePoint(val tempC: Int, val percent: Int)

/**
 * A temperature→fan-% curve for the Odin 3's Custom fan mode. The fan % is linearly interpolated
 * between [points] (sorted by temp) and held flat below the first / above the last point. Every result
 * is clamped to [MIN_PERCENT]..100 so a bad curve can never drop the fan below the vendor-safe floor,
 * and [effectivePercent] hard-forces 100% at/above [THERMAL_OVERRIDE_C] regardless of the curve (a
 * last-ditch thermal guard). The curve is the only thing the user edits; the controller turns its % into
 * a PWM duty and re-asserts it against the vendor fan service.
 */
data class FanCurve(val points: List<FanCurvePoint>) {

    /**
     * Fan % for [tempC], read off a SMOOTH monotone-cubic (Fritsch–Carlson) spline through the points, then
     * clamped to the safe range. The spline rounds the corners a straight-segment curve would have — so the
     * fan response is smooth — while *preserving monotonicity* (it never dips or overshoots between knees,
     * even with only a few points). Held flat below the first / above the last knee. With ≤2 points it
     * degenerates to a straight line.
     */
    fun percentFor(tempC: Int): Int = splineAt(tempC.toDouble()).roundToInt().coerceIn(MIN_PERCENT, 100)

    /**
     * Float-precision spline value (clamped) — same curve as [percentFor] but un-rounded, for DRAWING a smooth
     * line. Sampling the integer [percentFor] gives a visibly stair-stepped line; this doesn't.
     */
    fun percentForExact(tempC: Float): Float =
        splineAt(tempC.toDouble()).coerceIn(MIN_PERCENT.toDouble(), 100.0).toFloat()

    /** The monotone-cubic spline evaluated at [tempC] (un-rounded, un-clamped except the flat held ends). */
    private fun splineAt(tempC: Double): Double {
        val pts = points.sortedBy { it.tempC }.distinctBy { it.tempC }
        if (pts.isEmpty()) return MIN_PERCENT.toDouble()
        if (pts.size == 1) return pts.first().percent.coerceIn(MIN_PERCENT, 100).toDouble()
        if (tempC <= pts.first().tempC) return pts.first().percent.coerceIn(MIN_PERCENT, 100).toDouble()
        if (tempC >= pts.last().tempC) return pts.last().percent.coerceIn(MIN_PERCENT, 100).toDouble()

        val n = pts.size
        val xs = DoubleArray(n) { pts[it].tempC.toDouble() }
        val ys = DoubleArray(n) { pts[it].percent.toDouble() }
        val secant = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = secant[0]
        m[n - 1] = secant[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (secant[i - 1] * secant[i] <= 0.0) 0.0 else (secant[i - 1] + secant[i]) / 2.0
        }
        // Fritsch–Carlson limiter: keep the slopes monotonicity-preserving (no overshoot between knees).
        for (i in 0 until n - 1) {
            if (secant[i] == 0.0) {
                m[i] = 0.0; m[i + 1] = 0.0
            } else {
                val a = m[i] / secant[i]
                val b = m[i + 1] / secant[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val t = 3.0 / kotlin.math.sqrt(s)
                    m[i] = t * a * secant[i]
                    m[i + 1] = t * b * secant[i]
                }
            }
        }
        val i = (0 until n - 1).lastOrNull { tempC >= xs[it] } ?: 0
        val h = xs[i + 1] - xs[i]
        val t = (tempC - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]
    }

    /** [percentFor] with the hard thermal override applied. Use this to actually drive the fan. */
    fun effectivePercent(tempC: Int): Int =
        if (tempC >= THERMAL_OVERRIDE_C) 100 else percentFor(tempC)

    /**
     * This curve shifted by the Cooler/Quieter [bias] (% offset): every point's % moves up (cooler/louder)
     * or down (quieter), clamped to the safe [MIN_PERCENT]..100 range so the bias can never drop the fan
     * below the floor or exceed full. The result is the *effective* curve the controller drives and the
     * editor draws; the stored base curve is unchanged.
     */
    fun shiftedBy(bias: Int): FanCurve =
        if (bias == 0) this
        else FanCurve(points.map { it.copy(percent = (it.percent + bias).coerceIn(MIN_PERCENT, 100)) })

    /** Serialize to a compact "temp:percent,…" string for DataStore (same idiom as overlay elements). */
    fun serialize(): String = points.joinToString(",") { "${it.tempC}:${it.percent}" }

    companion object {
        const val MIN_PERCENT = 20        // vendor-safe floor; never write the fan below this
        const val THERMAL_OVERRIDE_C = 90 // at/above this temp, force 100% regardless of the curve
        const val DEFAULT_SLEW = 10       // response: %/second the fan ramps toward the curve target (smooth)
        const val MIN_SLEW = 2            // slowest/smoothest ramp the user can pick
        const val MAX_SLEW = 40           // fastest/snappiest ramp the user can pick
        const val MAX_BIAS = 30           // Cooler/Quieter bias caps at ±this many % of fan duty
        val DEFAULT = FanCurve(
            listOf(
                FanCurvePoint(30, 40), FanCurvePoint(50, 50),
                FanCurvePoint(80, 80), FanCurvePoint(90, 100),
            ),
        )

        /** Convert a fan % (clamped to MIN..100) to a PWM duty value for the given [period]. */
        fun percentToDuty(percent: Int, period: Int): Int =
            (period * percent.coerceIn(MIN_PERCENT, 100)) / 100

        /** Move [current] toward [target] by at most [stepPercent] — the response-smoothing lever. */
        fun easePercent(current: Int, target: Int, stepPercent: Int): Int {
            val step = stepPercent.coerceAtLeast(1)
            return when {
                target > current -> (current + step).coerceAtMost(target)
                target < current -> (current - step).coerceAtLeast(target)
                else -> current
            }
        }

        /** Parse a "temp:percent,…" string; skip malformed entries; blank/null → [DEFAULT]. */
        fun parse(s: String?): FanCurve {
            if (s.isNullOrBlank()) return DEFAULT
            val pts = s.split(",").mapNotNull { entry ->
                val parts = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                val tc = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val pc = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                FanCurvePoint(tc, pc)
            }
            return if (pts.isEmpty()) DEFAULT else FanCurve(pts)
        }
    }
}
