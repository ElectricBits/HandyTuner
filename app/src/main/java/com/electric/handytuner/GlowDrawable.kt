// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The design board's neon look: a rounded fill with an outline and a soft blur
 * of the outline's color around it. Elevation shadows can be tinted blue, but on
 * a near-black background they came out invisible, so the glow is drawn.
 *
 * The shape is inset by [glow] so the halo stays inside the view's own bounds.
 * BlurMaskFilter needs a software layer: the view using this sets
 * LAYER_TYPE_SOFTWARE (menu tiles are small, and the menu is only up briefly).
 */
class GlowDrawable(
    private val fill: Int,
    private val stroke: Int,
    private val strokeWidth: Float,
    private val radius: Float,
    private val glow: Float,
    private val glowAlpha: Int = 170,
) : Drawable() {
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        if (glow > 0) maskFilter = BlurMaskFilter(glow, BlurMaskFilter.Blur.NORMAL)
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    override fun draw(c: Canvas) {
        r.set(bounds); r.inset(glow, glow)
        if (glow > 0) {
            halo.color = stroke; halo.alpha = glowAlpha; halo.strokeWidth = strokeWidth + glow / 2
            c.drawRoundRect(r, radius, radius, halo)
        }
        body.color = fill; c.drawRoundRect(r, radius, radius, body)
        line.color = stroke; line.strokeWidth = strokeWidth; c.drawRoundRect(r, radius, radius, line)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
