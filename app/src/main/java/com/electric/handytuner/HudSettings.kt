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
import androidx.compose.foundation.layout.fillMaxSize
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
    // The theme reaches the overlay too, so it goes through the file with everything else; this process
    // reads it straight off Hand.
    fun theme(light: Boolean) { set(style.copy(light = light)); Hand.light = light }
    var tab by remember { mutableStateOf(0) }
    @Composable fun heading(t: String) =
        Text(t, color = Hand.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

    // Sub-tabs: only one group of controls is on screen at a time.
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Layout", "What it shows", "Hotkeys"), tab) { tab = it }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    heading("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !style.light, onClick = { theme(false) }, label = { Text("Dark") })
                        FilterChip(selected = style.light, onClick = { theme(true) }, label = { Text("Light") })
                    }
                    Text("The app, the HUD and the Quick Menu.", color = Hand.Muted, fontSize = 13.sp)

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
                                // The swatch is the colour the HUD will actually use on this theme.
                                Modifier.size(40.dp).background(Color(Hand.accent(argb)), CircleShape)
                                    .border(3.dp, if (style.accent == argb) Hand.Text else Color.Transparent, CircleShape)
                                    .clickable { set(style.copy(accent = argb)) },
                            )
                        }
                    }
                    // On the light theme "White" comes out as the text colour, so call it that.
                    val accentName = HudStyle.ACCENTS.firstOrNull { it.second == style.accent }?.first ?: ""
                    Text(if (Hand.light && accentName == "White") "Text" else accentName, color = Hand.Muted, fontSize = 13.sp)
                }
                1 -> {
                    heading("What the HUD shows")
                    HudStyle.Item.values().forEach { item ->
                        // The last ticked item can't be unticked: an empty HUD is just a blue outline.
                        fun flip(on: Boolean) { val next = if (on) style.shown + item else style.shown - item; if (next.isNotEmpty()) set(style.copy(shown = next)) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { flip(item !in style.shown) }) {
                            Checkbox(checked = item in style.shown, onCheckedChange = { flip(it) })
                            Text(item.label, color = Hand.Text)
                        }
                    }
                }
                else -> {
                    hotkeyRow()
                    Text("Show or hide the HUD in any game with the hotkey. Changes here apply live.", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }
}
