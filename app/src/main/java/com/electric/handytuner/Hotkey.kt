// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.view.KeyEvent
import java.io.File

/**
 * A button combo held together. Stored as a small file, not SharedPreferences,
 * because the app screen (which records it) and the :overlay process (which
 * listens for it) are separate processes, and prefs don't sync between them.
 * The overlay re-reads a combo when its file changes.
 */
class Hotkey(private val fileName: String, val default: Set<Int>) {
    fun file(ctx: Context) = File(ctx.filesDir, fileName)

    fun load(ctx: Context): Set<Int> = runCatching {
        file(ctx).readText().split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }.getOrNull()?.takeIf { it.size >= 2 } ?: default

    fun save(ctx: Context, keys: Set<Int>) = file(ctx).writeAtomic(keys.sorted().joinToString(","))

    companion object {
        /** Both back buttons + both sticks. On the Odin 2 Portal the back buttons report as C and Z (recorded 2026-09-29). */
        val HUD = Hotkey("hotkey", setOf(
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        ))
        /** Both sticks + R1, the owner's pick. Neither combo contains the other, so one press opens one thing. */
        val MENU = Hotkey("hotkey_menu", setOf(
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_R1,
        ))

        fun names(keys: Set<Int>) = keys.sorted().joinToString(" + ") { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }

        /** The Odin 2 Portal's back paddles: they report as C (left) and Z (right), checked with getevent 2026-10-16. */
        private val PADDLES = setOf(KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z)

        /** What the owner calls a button (paddles, L3/R3), not Android's name for it. [other] names the rest. */
        fun buttonName(code: Int, other: (Int) -> String = KeyMapper::label) = when (code) {
            KeyEvent.KEYCODE_BUTTON_C -> "Left paddle"
            KeyEvent.KEYCODE_BUTTON_Z -> "Right paddle"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            else -> other(code)
        }

        /** A combo for people: paddles first ("Both paddles" when both are in it), then the rest by code. */
        fun pretty(keys: Set<Int>, other: (Int) -> String = KeyMapper::label): String {
            val both = keys.containsAll(PADDLES)
            val rest = keys.filter { !(both && it in PADDLES) }.sortedBy { if (it in PADDLES) -1 else it }.map { buttonName(it, other) }
            return ((if (both) listOf("Both paddles") else emptyList()) + rest).joinToString(" + ")
        }
    }
}
