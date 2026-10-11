// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The PULSE engine's device-wide settings (merge-plan.md stage 4). Presets pick *which* mode a game gets;
 * this page sets how those modes behave everywhere, so nothing here is per game and presets don't change.
 * The engine's settings belong to the :pulse process, so every read and write goes through the link.
 */
/** Shown on the Tweaks page, under the always-on tweaks (owner's call: one tab, 2026-10-06). */
@Composable
fun TuningCards(ctx: Context) {
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
        note = "The PULSE engine isn't answering. Diagnostics shows why."
    }
    fun set(key: String, value: String) = scope.launch {
        val ok = withContext(Dispatchers.IO) { link.call { it.setEngineSetting(key, value) } == true }
        if (ok) s = withContext(Dispatchers.IO) { link.engineSettings() } ?: s
        note = if (ok) null else when (key) {
            "sleep" -> "Couldn't switch sleep underclock on: the CPU limits couldn't be read."
            else -> "That change didn't go through."
        }
    }

    run {
        if (safe) Text("Safe mode is on, so nothing here can change. Clear it in Diagnostics.", color = Hand.Warn, fontSize = 14.sp)
        note?.let { Text(it, color = Hand.Warn, fontSize = 14.sp) }
        val st = s ?: return@run
        val on = !safe

        HandCard(Modifier.fillMaxWidth()) {
            var target by remember(st) { mutableStateOf(st.optInt("fanTargetC", 78).toFloat()) }
            Title("Hold temp fan", "The temperature the Hold temp fan keeps the chip at. Applies to every preset that uses Hold temp.")
            Text("${target.roundToInt()} °C  ·  lower is cooler and louder", color = Hand.Blue, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            Slider(value = target, onValueChange = { target = it }, onValueChangeFinished = { set("fanTargetC", target.roundToInt().toString()) },
                valueRange = 60f..88f, steps = 27, enabled = on)
        }

        HandCard(Modifier.fillMaxWidth()) {
            Title("AutoTDP style", "How hard AutoTDP saves power while it holds your FPS. Applies to every preset that uses AutoTDP.")
            val styles = listOf("EFFICIENT" to "Efficient", "BALANCED" to "Balanced", "SMOOTH" to "Smooth")
            chips("Style", styles.map { (k, l) -> l to (st.optString("autoTdpBias") == k) }, on) { i -> set("autoTdpBias", styles[i].first) }
        }

        HandCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Title("Sleep underclock", "Slows the chip while the screen is off, and puts it back when you wake the Odin.") }
                Switch(checked = st.optBoolean("sleep"), enabled = on, onCheckedChange = { set("sleep", if (it) "on" else "off") },
                    modifier = Modifier.glowFocus(RoundedCornerShape(50)))
            }
        }

        HandCard(Modifier.fillMaxWidth()) {
            Title("Stick lights", "The lights around the sticks.")
            val modes = listOf("OFF" to "Off", "BATTERY" to "Battery", "HEAT" to "Heat", "MANUAL" to "Color")
            chips("Show", modes.map { (k, l) -> l to (st.optString("rgbMode") == k) }, on) { i -> set("rgbMode", modes[i].first) }
            if (st.optString("rgbMode") == "MANUAL") {
                var bright by remember(st) { mutableStateOf(st.optInt("rgbBrightness", 100).toFloat()) }
                val color = st.optString("rgbColor", "3F6BFF")
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { hex ->
                        val picked = hex.equals(color, ignoreCase = true)
                        androidx.compose.foundation.layout.Box(Modifier.size(if (picked) 40.dp else 32.dp).glowFocus(RoundedCornerShape(50))
                            .background(Color(0xFF000000 or hex.toLong(16)), CircleShape)
                            .clickable(enabled = on) { set("rgbColor", "$hex,${bright.roundToInt()}") })
                    }
                }
                Text("Brightness ${bright.roundToInt()}%", color = Hand.Blue, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(value = bright, onValueChange = { bright = it }, onValueChangeFinished = { set("rgbColor", "$color,${bright.roundToInt()}") },
                    valueRange = 0f..100f, enabled = on)
            }
        }
    }
}

private val SWATCHES = listOf("FF0000", "FF8000", "FFFF00", "00FF40", "00E5FF", "3F6BFF", "B000FF", "FFFFFF")

@Composable
private fun Title(name: String, what: String) {
    Text(name, color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    Text(what, color = Hand.Muted, fontSize = 14.sp)
}
