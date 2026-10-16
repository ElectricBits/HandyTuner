// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The PULSE engine's device-wide settings. Presets pick *which* mode a game gets; this sets how those modes
 * behave everywhere. The engine's settings belong to the :pulse process, so every read and write goes through the link.
 */
@Composable
fun PerformancePage(ctx: Context) {
    val link = remember { PulseLink(ctx, publishes = false) }
    DisposableEffect(Unit) { link.connect(); onDispose { link.disconnect() } }
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val safe = remember { SafeMode.active(ctx) }

    LaunchedEffect(Unit) {
        repeat(20) {   // the link comes up asynchronously; give it a few seconds
            s = withContext(Dispatchers.IO) { if (link.supports(Applied.Feature.ENGINE_SETTINGS)) link.engineSettings() else null }
            if (s != null) return@LaunchedEffect
            delay(250)
        }
        note = "The PULSE engine isn't answering. System › Permissions & apps shows why."
    }
    fun set(key: String, value: String) = scope.launch {
        val ok = withContext(Dispatchers.IO) { link.call { it.setEngineSetting(key, value) } == true }
        if (ok) s = withContext(Dispatchers.IO) { link.engineSettings() } ?: s
        note = if (ok) null else when (key) {
            "sleep" -> "Couldn't switch sleep underclock on: the CPU limits couldn't be read."
            else -> "That change didn't go through."
        }
    }

    ConsolePage {
        if (safe) Note("Safe mode is on, so nothing here can change. Clear it in System.", Hand.Warn)
        note?.let { Note(it, Hand.Warn) }
        val st = s ?: run { if (note == null) Note("Connecting to the PULSE engine…"); return@ConsolePage }
        val on = !safe

        RowGroup("Fan") {
            SliderRow("Hold temp target", st.optInt("fanTargetC", 78), 60..88, 1, { "$it °C" },
                hint = "For presets that use the Hold temp fan",
                help = "The temperature the Hold temp fan keeps the chip at. Lower is cooler and louder. Applies to every preset that uses Hold temp.",
                icon = Icons.Rounded.Thermostat, enabled = on) { set("fanTargetC", it.toString()) }
        }

        RowGroup("AutoTDP") {
            val styles = listOf("EFFICIENT" to "Efficient", "BALANCED" to "Balanced", "SMOOTH" to "Smooth")
            ChoiceRow("AutoTDP style", styles, styles.indexOfFirst { it.first == st.optString("autoTdpBias") }, { it.second },
                hint = "How hard it saves power while holding your FPS",
                help = "Efficient saves the most battery, Smooth keeps the most headroom. Applies to every preset that uses AutoTDP.",
                icon = Icons.Rounded.Speed, enabled = on) { set("autoTdpBias", styles[it].first) }
        }

        RowGroup("Sleep") {
            ToggleRow("Sleep underclock", st.optBoolean("sleep"), hint = "Slows the chip while the screen is off",
                help = "Saves battery in sleep, and puts the speed back the moment you wake the Odin.",
                icon = Icons.Rounded.Bedtime, enabled = on) { set("sleep", if (it) "on" else "off") }
        }

        RowGroup("Stick lights") {
            val modes = listOf("OFF" to "Off", "BATTERY" to "Battery", "HEAT" to "Heat", "MANUAL" to "Color")
            val mode = st.optString("rgbMode")
            ChoiceRow("Lights", modes, modes.indexOfFirst { it.first == mode }, { it.second },
                hint = "The rings around the sticks",
                help = "Battery and Heat change color as the battery drains or the chip warms. Color is one color you pick.",
                icon = Icons.Rounded.Lightbulb, enabled = on) { set("rgbMode", modes[it].first) }
            if (mode == "MANUAL") {
                val color = st.optString("rgbColor", "3F6BFF")
                val bright = st.optInt("rgbBrightness", 100)
                ChoiceRow("Color", SWATCHES, SWATCHES.indexOfFirst { it.first.equals(color, ignoreCase = true) }, { it.second },
                    icon = Icons.Rounded.Palette, enabled = on, dot = { Color(0xFF000000 or it.first.toLong(16)) }) {
                    set("rgbColor", "${SWATCHES[it].first},$bright")
                }
                SliderRow("Brightness", bright, 0..100, 5, { "$it%" }, icon = Icons.Rounded.Brightness6, enabled = on) {
                    set("rgbColor", "$color,$it")
                }
            }
        }
    }
}

/** The stick-light colors on offer: what the hardware shows well. */
private val SWATCHES = listOf(
    "FF0000" to "Red", "FF8000" to "Orange", "FFFF00" to "Yellow", "00FF40" to "Green",
    "00E5FF" to "Cyan", "3F6BFF" to "Blue", "B000FF" to "Purple", "FFFFFF" to "White",
)
