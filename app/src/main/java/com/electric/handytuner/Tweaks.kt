// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Tweaks the owner can switch on for everything, not just one game (owner's call, 2026-09-30). A file,
 * like the other settings, because the :overlay process applies them.
 */
object Tweaks {
    fun file(ctx: Context) = File(ctx.filesDir, "tweaks.properties")
    private fun load(ctx: Context) = runCatching { Properties().apply { file(ctx).inputStream().use { load(it) } } }.getOrDefault(Properties())
    private fun flag(ctx: Context, key: String, default: Boolean) = load(ctx).getProperty(key)?.toBoolean() ?: default
    private fun set(ctx: Context, key: String, on: Boolean) = file(ctx).storeAtomic(load(ctx).apply { setProperty(key, on.toString()) })

    fun lowLatency(ctx: Context) = flag(ctx, "lowLatency", false)
    fun setLowLatency(ctx: Context, on: Boolean) = set(ctx, "lowLatency", on)

    /** Keep Odin Assistant's game detection off (it fights the PULSE engine). On by default; the owner's choice. */
    fun assistantOff(ctx: Context) = flag(ctx, "assistantOff", true)
    fun setAssistantOff(ctx: Context, on: Boolean) = set(ctx, "assistantOff", on)
}
