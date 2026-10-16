// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The HUD: every change is saved at once, and a shown HUD redraws within a second. [hotkeys] are the record rows. */
@Composable
fun HudSettings(ctx: Context, hotkeys: @Composable () -> Unit) {
    var style by remember { mutableStateOf(HudStyle.load(ctx)) }
    fun set(s: HudStyle) { style = s; HudStyle.save(ctx, s) }

    ConsolePage {
        RowGroup("Look") {
            ChoiceRow("Layout", listOf(false, true), if (style.compact) 1 else 0, { if (it) "Compact" else "Full" },
                hint = if (style.compact) "One line: FPS, ping and Wi-Fi" else "Everything you picked below",
                icon = Icons.Rounded.ViewAgenda) { set(style.copy(compact = it == 1)) }
            val corners = HudStyle.Corner.values().toList()
            ChoiceRow("Position", corners, corners.indexOf(style.corner), { it.label }, icon = Icons.Rounded.PictureInPicture) { set(style.copy(corner = corners[it])) }
            val sizes = HudStyle.Size.values().toList()
            ChoiceRow("Size", sizes, sizes.indexOf(style.size), { it.label }, hint = "TV is for a docked Odin, read from the sofa",
                icon = Icons.Rounded.AspectRatio) { set(style.copy(size = sizes[it])) }
            SliderRow("Background", style.opacity, 0..100, 5, { "$it%" }, hint = "How solid the box behind the numbers is",
                icon = Icons.Rounded.Opacity) { set(style.copy(opacity = it)) }
            val accents = HudStyle.ACCENTS
            // On the light theme "White" comes out as the text colour, so call it that; each dot is the color the HUD will use.
            ChoiceRow("Accent color", accents, accents.indexOfFirst { it.second == style.accent },
                { (name, _) -> if (Hand.light && name == "White") "Text" else name },
                hint = "The outline and glow", icon = Icons.Rounded.Palette, dot = { Color(Hand.accent(it.second)) }) {
                set(style.copy(accent = accents[it].second))
            }
        }

        RowGroup("What it shows") {
            HudStyle.Item.values().forEach { item ->
                val on = item in style.shown
                // The last ticked item can't be unticked: an empty HUD is just a blue outline.
                val last = on && style.shown.size == 1
                ToggleRow(item.label, on, hint = if (last) "Keep at least one" else null, enabled = !last) {
                    set(style.copy(shown = if (it) style.shown + item else style.shown - item))
                }
            }
        }

        RowGroup("Hotkeys") { hotkeys() }
        Note("Show or hide the HUD in any game with its hotkey. Changes here apply live.")
    }
}
