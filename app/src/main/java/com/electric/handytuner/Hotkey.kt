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
    }
}
