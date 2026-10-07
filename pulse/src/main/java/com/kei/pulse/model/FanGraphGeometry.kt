package com.kei.pulse.model

import kotlin.math.roundToInt

/**
 * Coordinate mapping for the EVGA-style curve editor canvas. X = temperature (left→right over
 * [TEMP_MIN_C]..[TEMP_MAX_C]); Y = fan percent with 100% at the TOP (y=0) and 0% at the bottom (y=1),
 * the way a fan curve is conventionally drawn. Coordinates are normalized 0..1 so the Composable can
 * scale them to whatever canvas size it gets. Pure — the drawing/gestures are verified on-device.
 */
object FanGraphGeometry {
    const val TEMP_MIN_C = 20
    const val TEMP_MAX_C = 100

    /** (tempC, percent) → normalized (x 0..1 left→right, y 0..1 top→bottom). */
    fun toNorm(tempC: Int, percent: Int): Pair<Float, Float> {
        val x = ((tempC - TEMP_MIN_C).toFloat() / (TEMP_MAX_C - TEMP_MIN_C)).coerceIn(0f, 1f)
        val y = (1f - percent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
        return x to y
    }

    /** normalized (x, y) → (tempC, percent), clamped to the axis range and the safe fan floor. */
    fun fromNorm(x: Float, y: Float): FanCurvePoint {
        val temp = (TEMP_MIN_C + x.coerceIn(0f, 1f) * (TEMP_MAX_C - TEMP_MIN_C))
            .roundToInt().coerceIn(TEMP_MIN_C, TEMP_MAX_C)
        val pct = ((1f - y.coerceIn(0f, 1f)) * 100f)
            .roundToInt().coerceIn(FanCurve.MIN_PERCENT, 100)
        return FanCurvePoint(temp, pct)
    }
}
