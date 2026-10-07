package com.kei.pulse.model

/**
 * Pure edit ops for the curve editor. The Compose drag gesture computes a new (temp, %) for the knee the
 * user is dragging and calls [movePoint]; this keeps the curve ordered and clamped so a drag can never
 * cross a neighboring knee, invert the temperature order, or push the fan below the safe floor / past 100%.
 * Operates on a temperature-sorted list (the editor keeps it sorted); [index] is the position in that list.
 */
object FanCurveEditing {
    /** Minimum °C gap kept between adjacent knees so two points can't land on the same temperature. */
    private const val MIN_GAP_C = 1

    /** A new point must be at least this far (°C) from an existing knee, so taps don't cram points together. */
    private const val ADD_MIN_GAP_C = 3

    /** Curve must keep at least 2 knees (a line); cap the count so the graph doesn't get cluttered. */
    const val MIN_POINTS = 2
    const val MAX_POINTS = 10

    fun movePoint(points: List<FanCurvePoint>, index: Int, newTempC: Int, newPercent: Int): List<FanCurvePoint> {
        if (index !in points.indices) return points
        val lo = if (index > 0) points[index - 1].tempC + MIN_GAP_C else FanGraphGeometry.TEMP_MIN_C
        val hi = if (index < points.lastIndex) points[index + 1].tempC - MIN_GAP_C else FanGraphGeometry.TEMP_MAX_C
        val temp = newTempC.coerceIn(lo, hi)
        val pct = newPercent.coerceIn(FanCurve.MIN_PERCENT, 100)
        return points.toMutableList().also { it[index] = FanCurvePoint(temp, pct) }
    }

    /**
     * Drag a knee when a Cooler/Quieter [bias] is shown: the graph draws the EFFECTIVE curve (base + bias),
     * so the finger position [newEffectivePercent] is in effective space. Update the BASE so base + bias lands
     * under the finger — i.e. store `effective − bias` (clamped by [movePoint] to the safe floor..100).
     */
    fun movePointBiased(
        points: List<FanCurvePoint>,
        index: Int,
        newTempC: Int,
        newEffectivePercent: Int,
        bias: Int,
    ): List<FanCurvePoint> = movePoint(points, index, newTempC, newEffectivePercent - bias)

    /**
     * Add a knee at [tempC]/[percent] (clamped to the axis + safe floor), keeping the list temperature-sorted.
     * Rejected (returns the list unchanged) if it would land within [ADD_MIN_GAP_C] of an existing knee or push
     * past [MAX_POINTS] — so a stray tap can't cram points or explode the curve.
     */
    fun addPoint(points: List<FanCurvePoint>, tempC: Int, percent: Int): List<FanCurvePoint> {
        if (points.size >= MAX_POINTS) return points
        val t = tempC.coerceIn(FanGraphGeometry.TEMP_MIN_C, FanGraphGeometry.TEMP_MAX_C)
        if (points.any { kotlin.math.abs(it.tempC - t) < ADD_MIN_GAP_C }) return points
        val p = percent.coerceIn(FanCurve.MIN_PERCENT, 100)
        return (points + FanCurvePoint(t, p)).sortedBy { it.tempC }
    }

    /** Remove the knee at [index], unless that would drop below [MIN_POINTS] (a curve needs at least a line). */
    fun removePoint(points: List<FanCurvePoint>, index: Int): List<FanCurvePoint> {
        if (points.size <= MIN_POINTS || index !in points.indices) return points
        return points.toMutableList().also { it.removeAt(index) }
    }
}
