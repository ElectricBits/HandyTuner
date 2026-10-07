// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The HUD tab: every change is saved at once, and a shown HUD redraws within a second. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudSettings(ctx: Context, hotkeyRow: @Composable () -> Unit) {
    var style by remember { mutableStateOf(HudStyle.load(ctx)) }
    fun set(s: HudStyle) { style = s; HudStyle.save(ctx, s) }
    @Composable fun heading(t: String) =
        Text(t, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    Column(Modifier.verticalScroll(rememberScrollState())) {
        hotkeyRow()
        Text("Show or hide the HUD in any game with the hotkey. Changes here apply live.", color = Color.Gray, fontSize = 13.sp)

        heading("Layout")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !style.compact, onClick = { set(style.copy(compact = false)) }, label = { Text("Full") })
            FilterChip(selected = style.compact, onClick = { set(style.copy(compact = true)) }, label = { Text("Compact: FPS, ping, Wi-Fi") })
        }

        heading("Position")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudStyle.Corner.values().forEach { c ->
                FilterChip(selected = style.corner == c, onClick = { set(style.copy(corner = c)) }, label = { Text(c.label) })
            }
        }

        heading("Size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudStyle.Size.values().forEach { z ->
                FilterChip(selected = style.size == z, onClick = { set(style.copy(size = z)) }, label = { Text(z.label) })
            }
        }

        heading("Background opacity: ${style.opacity}%")
        Slider(value = style.opacity.toFloat(), onValueChange = { style = style.copy(opacity = it.toInt()) },
            onValueChangeFinished = { HudStyle.save(ctx, style) }, valueRange = 0f..100f, modifier = Modifier.width(420.dp))

        heading("Accent color")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudStyle.ACCENTS.forEach { (name, argb) ->
                Box(
                    Modifier.size(40.dp).background(Color(argb), CircleShape)
                        .border(3.dp, if (style.accent == argb) Color.White else Color.Transparent, CircleShape)
                        .clickable { set(style.copy(accent = argb)) },
                )
            }
        }
        Text(HudStyle.ACCENTS.firstOrNull { it.second == style.accent }?.first ?: "", color = Color.Gray, fontSize = 13.sp)

        heading("What the HUD shows")
        HudStyle.Item.values().forEach { item ->
            // The last ticked item can't be unticked: an empty HUD is just a blue outline.
            fun flip(on: Boolean) { val next = if (on) style.shown + item else style.shown - item; if (next.isNotEmpty()) set(style.copy(shown = next)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { flip(item !in style.shown) }) {
                Checkbox(checked = item in style.shown, onCheckedChange = { flip(it) })
                Text(item.label, color = Color.White)
            }
        }
    }
}
