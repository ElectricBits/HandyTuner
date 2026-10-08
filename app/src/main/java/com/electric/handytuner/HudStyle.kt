// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * How the HUD looks and what it shows. A file rather than SharedPreferences,
 * like [Hotkey]: the settings screen writes it, the :overlay process reads it
 * and redraws when it changes.
 */
data class HudStyle(
    val corner: Corner = Corner.TOP_LEFT,
    val size: Size = Size.M,
    val opacity: Int = 88,                       // background, percent
    val accent: Int = ACCENTS.first().second,
    val shown: Set<Item> = Item.values().toSet(),
    /** One line: FPS, the ping that matters (game if online, else net), Wi-Fi. */
    val compact: Boolean = false,
) {
    enum class Corner(val label: String) { TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right") }
    enum class Size(val label: String, val scale: Float) { S("Small", 0.8f), M("Medium", 1f), L("Large", 1.25f), XL("TV", 1.7f) }
    enum class Item(val label: String) {
        FPS("FPS"), HZ("Refresh rate (Hz)"), GAME("Game ping"), NET("Net test"), WIFI("Wi-Fi"), RAM("Free RAM"),
        SESSION("Battery time left"), CLOCK("Clock"), BOTTLENECK("Bottleneck line"), PAD("Controller battery"),
        // From the Pulse fork (its own HUD is off, so these replace it; docs/feature-registry.md F4).
        TEMPS("CPU/GPU °C (Pulse)"), WATTS("Watts (Pulse)"), LOAD("CPU/GPU load (Pulse)"), MODE("Pulse mode"),
    }

    companion object {
        /** HandyHelper blue first: it's the logo's. */
        val ACCENTS = listOf(
            "HandyHelper blue" to 0xFF0389FB.toInt(), "Cyan" to 0xFF00E5FF.toInt(), "Magenta" to 0xFFFF2BD6.toInt(),
            "Lime" to 0xFF39FF14.toInt(), "Orange" to 0xFFFF8A00.toInt(), "White" to 0xFFFFFFFF.toInt(),
        )

        fun file(ctx: Context) = File(ctx.filesDir, "hud.properties")

        fun load(ctx: Context): HudStyle = runCatching {
            val p = Properties().apply { file(ctx).inputStream().use { load(it) } }
            val d = HudStyle()
            HudStyle(
                corner = runCatching { Corner.valueOf(p.getProperty("corner")) }.getOrDefault(d.corner),
                size = runCatching { Size.valueOf(p.getProperty("size")) }.getOrDefault(d.size),
                opacity = p.getProperty("opacity")?.toIntOrNull()?.coerceIn(0, 100) ?: d.opacity,
                accent = p.getProperty("accent")?.toLongOrNull()?.toInt() ?: d.accent,
                shown = p.getProperty("shown")?.split(",")?.mapNotNull { runCatching { Item.valueOf(it) }.getOrNull() }?.toSet()?.takeIf { it.isNotEmpty() } ?: d.shown,   // nothing ticked = an empty box: show all
                compact = p.getProperty("compact")?.toBoolean() ?: d.compact,
            )
        }.getOrDefault(HudStyle())

        fun save(ctx: Context, s: HudStyle) = file(ctx).storeAtomic(
            Properties().apply {
                setProperty("corner", s.corner.name); setProperty("size", s.size.name)
                setProperty("opacity", s.opacity.toString()); setProperty("accent", s.accent.toUInt().toString())
                setProperty("shown", s.shown.joinToString(",") { i -> i.name })
                setProperty("compact", s.compact.toString())
            })
    }
}
