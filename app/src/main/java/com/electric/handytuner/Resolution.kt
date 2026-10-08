// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

/**
 * Resolution, like PULSE's render scale (`wm size` + `wm density` through PServer), with more choices: the Odin's
 * own screen by percent, and the TV by height while docked. Fewer pixels is less GPU work and, on a 4K TV, about
 * 300 MB less memory. Android keeps an override per screen; Reset and safe mode put both back.
 */
data class Resolution(
    /** Percent of the Odin's own screen; 100 is native. */
    val screenPct: Int = 100,
    /** Height the TV draws at while docked; 0 is the TV's own. */
    val tvHeight: Int = 0,
    /** Scale the density too, so text and buttons keep their size (PULSE's way). Off: everything grows or shrinks. */
    val keepSize: Boolean = true,
) {
    /** A screen's size and density as `wm` sees it, in its own orientation (a TV reports "2160x3840"). */
    data class Spec(val w: Int, val h: Int, val density: Int)

    companion object {
        val SCALES = listOf(100, 90, 85, 80, 75, 67, 60, 50)
        val TV_HEIGHTS = listOf(0, 1440, 1080, 720)

        /** [native] scaled to [pct]; null at 100 (native). */
        fun scaled(native: Spec, pct: Int, keepSize: Boolean): Spec? = if (pct >= 100) null else
            Spec(native.w * pct / 100 / 2 * 2, native.h * pct / 100 / 2 * 2, if (keepSize) native.density * pct / 100 else native.density)

        /** [native] with its short side at [height]; null when that's not smaller than it is. */
        fun atHeight(native: Spec, height: Int, keepSize: Boolean): Spec? {
            val short = minOf(native.w, native.h)
            return if (height <= 0 || height >= short) null else scaled(native, height * 100 / short, keepSize)
                ?.let { s -> if (native.w < native.h) s.copy(w = height, h = native.h * height / short / 2 * 2) else s.copy(h = height, w = native.w * height / short / 2 * 2) }
        }

        fun parse(size: String, density: String): Spec? {
            val (w, h) = Regex("""Physical size: (\d+)x(\d+)""").find(size)?.destructured ?: return null
            val d = Regex("""Physical density: (\d+)""").find(density)?.groupValues?.get(1)?.toInt() ?: 0
            return Spec(w.toInt(), h.toInt(), d)
        }

        private fun file(ctx: Context) = File(ctx.filesDir, "resolution.properties")

        fun load(ctx: Context): Resolution = runCatching {
            val p = Properties().apply { file(ctx).inputStream().use { load(it) } }
            Resolution(p.getProperty("screenPct")?.toIntOrNull()?.takeIf { it in SCALES } ?: 100,
                p.getProperty("tvHeight")?.toIntOrNull()?.takeIf { it in TV_HEIGHTS } ?: 0,
                p.getProperty("keepSize")?.toBooleanStrictOrNull() ?: true)
        }.getOrDefault(Resolution())

        fun save(ctx: Context, r: Resolution) = file(ctx).storeAtomic(Properties().apply {
            setProperty("screenPct", r.screenPct.toString()); setProperty("tvHeight", r.tvHeight.toString())
            setProperty("keepSize", r.keepSize.toString())
        })

        fun tv(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        private fun native(id: Int) = parse(PServer.run("wm size -d $id").orEmpty(), PServer.run("wm density -d $id").orEmpty())

        /** Sets one screen to [want], or back to its own size and density for null. Blocking: run off the main thread. */
        private fun set(id: Int, want: Spec?) {
            if (want == null) PServer.run("wm size reset -d $id; wm density reset -d $id")
            else PServer.run("wm size ${want.w}x${want.h} -d $id; wm density ${want.density} -d $id")
        }

        /** The Odin's own screen. */
        fun applyScreen(r: Resolution) {
            val n = native(Display.DEFAULT_DISPLAY) ?: return
            set(Display.DEFAULT_DISPLAY, scaled(n, r.screenPct, r.keepSize))
        }

        /** The TV, if one is connected: [docked] false puts it back to its own. */
        fun applyTv(ctx: Context, r: Resolution, docked: Boolean = true) {
            val tv = tv(ctx) ?: return
            val n = native(tv.displayId) ?: return
            set(tv.displayId, if (docked) atHeight(n, r.tvHeight, r.keepSize) else null)
        }

        /** Reset to stock and safe mode: both screens back to their own size and density. */
        fun putBack(ctx: Context) {
            set(Display.DEFAULT_DISPLAY, null)
            tv(ctx)?.let { set(it.displayId, null) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResolutionCard(ctx: Context) {
    var r by remember { mutableStateOf(Resolution.load(ctx)) }
    fun save(n: Resolution, screen: Boolean, tv: Boolean) {
        r = n; Resolution.save(ctx, n)
        thread { if (screen) Resolution.applyScreen(n); if (tv) Resolution.applyTv(ctx, n) }
    }
    HandCard(Modifier.fillMaxWidth()) {
        Text("Resolution", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("Fewer pixels: less work for the graphics chip, and on a 4K TV about 300 MB less memory. A running game may " +
            "pause once when it changes. Reset everything to stock puts both back.", color = Hand.Muted, fontSize = 14.sp)
        Text("The Odin's screen", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Resolution.SCALES.forEach { p ->
                FilterChip(selected = r.screenPct == p, onClick = { save(r.copy(screenPct = p), screen = true, tv = false) },
                    label = { Text(if (p == 100) "Native" else "$p%") }, modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
            }
        }
        Text("The TV, while docked", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Resolution.TV_HEIGHTS.forEach { h ->
                FilterChip(selected = r.tvHeight == h, onClick = { save(r.copy(tvHeight = h), screen = false, tv = true) },
                    label = { Text(if (h == 0) "The TV's own" else "${h}p") }, modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
            }
        }
        SettingSwitch("Keep text and buttons the same size", "Off: everything gets bigger at a lower resolution.", r.keepSize) {
            save(r.copy(keepSize = it), screen = true, tv = true)
        }
    }
}
