// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller settings. Only the button layout is set here: it is one system
 * setting. Calibration, L2/R2 mode, button mapping and the key test already
 * live in AYN's own apps, which also tell the controller driver to apply them,
 * so HandyTuner opens those rather than half-copying them. Stick lights are
 * Pulse's.
 */
@Composable
fun ControllerPage(ctx: Context) {
    val scope = rememberCoroutineScope()
    // flip_button_layout: 1 = Xbox layout (A bottom, B right), 0 = Nintendo-style (A/B swapped), as the Odin labels it.
    var flipped by remember { mutableStateOf(Settings.System.getInt(ctx.contentResolver, "flip_button_layout", 1)) }
    fun setLayout(v: Int) {
        flipped = v
        scope.launch(Dispatchers.IO) {
            Originals.remember(ctx, "flip_button_layout")
            PServer.run("settings put system flip_button_layout $v")
            val now = Settings.System.getInt(ctx.contentResolver, "flip_button_layout", v)
            withContext(Dispatchers.Main) { flipped = now }
        }
    }
    fun open(pkg: String, cls: String) = runCatching {
        ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    var tab by remember { mutableStateOf(0) }

    // Sub-tabs: the layout setting, the pads & test tools, and the key-mapping housekeeping.
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Layout", "Gamepads", "Mapping"), tab) { tab = it }
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> {
                    HandCard(Modifier.fillMaxWidth()) {
                        Text("Button layout", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text("Which face button is A. Try it in the Key Test.", color = Hand.Muted, fontSize = 14.sp)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = flipped == 1, onClick = { setLayout(1) }, label = { Text("Xbox (A bottom)") },
                                modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
                            FilterChip(selected = flipped == 0, onClick = { setLayout(0) }, label = { Text("Swapped (A right)") },
                                modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
                        }
                    }
                    HandCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Odin's controller settings", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                // What the Odin 2 Portal's firmware has there (2026-10-08); it has no deadzone or invert options.
                                Text("Controller style, L2/R2 mode, and what M1, M2 and Back do: in Odin Settings, under Controller Settings.",
                                    color = Hand.Muted, fontSize = 14.sp)
                            }
                            Button(onClick = { open("com.odin.settings", "com.ro.settings.activity.MainSettingsActivity") },
                                Modifier.glowFocus(RoundedCornerShape(50))) { Text("Open Odin settings") }
                        }
                    }
                }
                1 -> {
                    HandCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Key Test & stick calibration", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                Text("AYN's own test screen: see every press, and recalibrate drifting sticks.", color = Hand.Muted, fontSize = 14.sp)
                            }
                            Button(onClick = { open("com.odin.gameassistant", "com.ro.gameassistant.activity.GamepadTestActivity") },
                                Modifier.glowFocus(RoundedCornerShape(50))) { Text("Open Key Test") }
                        }
                    }
                    ConnectedPadsCard(ctx)
                    ButtonTestCard(ctx)
                    PadActionsCard(ctx)
                    PadSetupCard(ctx)
                }
                else -> KeyMappingCard(ctx)
            }
        }
    }
}

/** Key mapping housekeeping: which games have layouts, export/import, and how mapped taps behave. */
@Composable
private fun KeyMappingCard(ctx: Context) {
    val scope = rememberCoroutineScope()
    var maps by remember { mutableStateOf(KeyMapper.all(ctx)) }
    var set by remember { mutableStateOf(KeyMapper.settings(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    fun save(n: KeyMapper.Settings) { set = n; KeyMapper.saveSettings(ctx, n) }
    fun label(pkg: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    HandCard(Modifier.fillMaxWidth()) {
        Text("Key mapping", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("Buttons to screen taps for Android touch games. Make a layout from the Quick Menu while the game is open: " +
            "press a placed button again to switch tap → hold → auto-fire. A layout made while an external controller is " +
            "connected is saved for the controller, so it can differ from the Odin's own.", color = Hand.Muted, fontSize = 14.sp)
        if (maps.isEmpty()) Text("No layouts yet.", color = Hand.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        maps.forEach { (pkg, spots) ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val pad = pkg.endsWith(KeyMapper.PAD)
                Text("${label(pkg.removeSuffix(KeyMapper.PAD))}${if (pad) " (controller)" else ""}: ${spots.size} button${if (spots.size == 1) "" else "s"}", color = Hand.Text, fontSize = 16.sp,
                    modifier = Modifier.weight(1f))
                Button(onClick = { KeyMapper.set(ctx, pkg, emptyList()); maps = KeyMapper.all(ctx) },
                    Modifier.glowFocus(RoundedCornerShape(50))) { Text("Delete") }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scope.launch(Dispatchers.IO) {
                val ok = KeyMapper.exportAll(ctx)
                withContext(Dispatchers.Main) { note = if (ok) "Saved to Downloads/HandyTuner-keymaps.json" else "Couldn't save the file" }
            } }, Modifier.glowFocus(RoundedCornerShape(50)), enabled = maps.isNotEmpty()) { Text("Export") }
            Button(onClick = { scope.launch(Dispatchers.IO) {
                val n = KeyMapper.importAll(ctx)
                withContext(Dispatchers.Main) {
                    maps = KeyMapper.all(ctx)
                    note = if (n < 0) "No Downloads/HandyTuner-keymaps.json to import" else "Imported $n game layout${if (n == 1) "" else "s"}"
                }
            } }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Import") }
        }
        note?.let { Text(it, color = Hand.Blue, fontSize = 14.sp) }

        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show the layout in games", color = Hand.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = set.show, onCheckedChange = { save(set.copy(show = it)) },
                modifier = Modifier.glowFocus(RoundedCornerShape(50)))
        }
        @Composable fun slider(label: String, value: Int, range: IntRange, unit: String, onDone: (Int) -> Unit) {
            var v by remember(value) { mutableStateOf(value.toFloat()) }
            Text("$label: ${v.toInt()}$unit", color = Hand.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.material3.Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v.toInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(), modifier = Modifier.fillMaxWidth(0.6f))
        }
        slider("How see-through the layout is", set.opacity, 10..90, "%") { save(set.copy(opacity = it)) }
        slider("Tap length", set.tapMs, 20..200, " ms") { save(set.copy(tapMs = it)) }
        slider("Auto-fire speed", set.autoRate, 2..20, " taps/s") { save(set.copy(autoRate = it)) }
    }
}
