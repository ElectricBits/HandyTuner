// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/**
 * The key-mapping editor, drawn over the live game. The D-pad moves a crosshair
 * (faster the longer it's held); any other button drops itself at the crosshair,
 * and the same button again on its own spot flips tap ↔ hold. Select undoes,
 * Start saves and closes. It takes every key while open, so the game sees none.
 */
class KeyMapEditor(private val svc: AccessibilityService, private val font: Typeface, private val onClose: () -> Unit) {
    private var view: EditorView? = null
    private var pkg = ""
    private val spots = mutableListOf<KeyMapper.Spot>()
    private var cx = 0.5f; private var cy = 0.5f
    private var speed = 0.004f
    val open get() = view != null

    fun start(gamePkg: String) {
        pkg = gamePkg
        spots.clear(); spots += KeyMapper.get(svc, pkg)
        cx = 0.5f; cy = 0.5f
        val v = EditorView()
        svc.getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ))
        view = v
    }

    private fun close(save: Boolean) {
        if (save) KeyMapper.set(svc, pkg, spots.toList())
        view?.let { svc.getSystemService(WindowManager::class.java).removeView(it) }
        view = null
        onClose()
    }

    /** Called from the service's onKeyEvent while open; always takes the key. */
    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                speed = if (e.repeatCount == 0) 0.004f else minOf(speed * 1.25f, 0.03f)
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> cx -= speed
                    KeyEvent.KEYCODE_DPAD_RIGHT -> cx += speed
                    KeyEvent.KEYCODE_DPAD_UP -> cy -= speed
                    else -> cy += speed
                }
                cx = cx.coerceIn(0f, 1f); cy = cy.coerceIn(0f, 1f)
            }
            KeyEvent.KEYCODE_BUTTON_START -> if (down) { close(save = true); return true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (down && spots.isNotEmpty()) spots.removeAt(spots.lastIndex)
            else -> if (down && e.repeatCount == 0) {
                val same = spots.indexOfFirst { it.key == e.keyCode }
                val near = same >= 0 && kotlin.math.hypot(spots[same].x - cx, spots[same].y - cy) < 0.03f
                if (near) spots[same] = spots[same].copy(mode = KeyMapper.Mode.values()[(spots[same].mode.ordinal + 1) % KeyMapper.Mode.values().size])
                else { if (same >= 0) spots.removeAt(same); spots += KeyMapper.Spot(e.keyCode, cx, cy) }
            }
        }
        view?.invalidate()
        return true
    }

    private inner class EditorView : View(svc) {
        private val dim = Paint().apply { color = 0x55000000 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF0389FB.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA0A1428.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = font }
        private val bar = Paint().apply { color = 0xE0050510.toInt() }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val r = height * 0.045f
            spots.forEach { s ->
                val x = s.x * width; val y = s.y * height
                c.drawCircle(x, y, r, fill)
                ring.color = when (s.mode) { KeyMapper.Mode.HOLD -> 0xFFFF2BD6.toInt(); KeyMapper.Mode.AUTO -> 0xFFFFB300.toInt(); else -> 0xFF0389FB.toInt() }
                c.drawCircle(x, y, r, ring)
                text.textSize = r * 0.8f
                c.drawText(KeyMapper.label(s.key), x, y + r * 0.28f, text)
                if (s.mode != KeyMapper.Mode.TAP) {
                    text.textSize = r * 0.45f
                    c.drawText(if (s.mode == KeyMapper.Mode.HOLD) "hold" else "auto-fire", x, y + r * 1.6f, text)
                }
            }
            // Crosshair.
            val x = cx * width; val y = cy * height
            ring.color = 0xFF39FF14.toInt()
            c.drawCircle(x, y, r * 0.6f, ring)
            c.drawLine(x - r, y, x - r * 0.3f, y, ring); c.drawLine(x + r * 0.3f, y, x + r, y, ring)
            c.drawLine(x, y - r, x, y - r * 0.3f, ring); c.drawLine(x, y + r * 0.3f, x, y + r, ring)
            // Help bar.
            c.drawRect(0f, 0f, width.toFloat(), height * 0.07f, bar)
            text.textSize = height * 0.028f
            c.drawText("D-pad: move  ·  any button: put it here (again: tap → hold → auto-fire)  ·  Select: undo  ·  Start: save",
                width / 2f, height * 0.047f, text)
        }
    }
}

/**
 * "Show key map" (Odin Assistant's key view): the current game's mapped buttons as faint
 * circles during play. Never touchable, so taps go straight through to the game.
 */
class KeyMapView(private val svc: AccessibilityService, private val font: Typeface) {
    private var view: View? = null
    private var spots: List<KeyMapper.Spot> = emptyList()
    private var opacity = 35

    fun show(list: List<KeyMapper.Spot>, opacityPct: Int) {
        spots = list; opacity = opacityPct
        if (list.isEmpty()) { hide(); return }
        view?.let { it.invalidate(); return }
        val v = object : View(svc) {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
            private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = font }
            override fun onDraw(c: Canvas) {
                val a = (opacity.coerceIn(5, 100) * 255 / 100) shl 24
                val r = height * 0.04f
                spots.forEach { s ->
                    val x = s.x * width; val y = s.y * height
                    ring.color = a or when (s.mode) { KeyMapper.Mode.HOLD -> 0xFF2BD6; KeyMapper.Mode.AUTO -> 0xFFB300; else -> 0x0389FB }
                    c.drawCircle(x, y, r, ring)
                    text.color = a or 0xFFFFFF; text.textSize = r * 0.75f
                    c.drawText(KeyMapper.label(s.key), x, y + r * 0.27f, text)
                }
            }
        }
        svc.getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ))
        view = v
    }

    fun hide() {
        view?.let { runCatching { svc.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }
}
