// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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

/** The owner's own presets, and sharing them as Downloads/HandyTuner-presets.json (F14). B / Back returns. */
@Composable
fun PresetsEditor(ctx: Context, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(CustomPresets.all(ctx)) }
    var edit by remember { mutableStateOf<PresetDef?>(null) }       // being made or changed
    var note by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler { if (edit != null) edit = null else onDone() }

    edit?.let { d ->
        PresetForm(d, taken = list.filter { it.key != d.key }.map { it.label }) { saved ->
            if (saved != null) {
                list = list.filter { it.key != d.key } + saved
                CustomPresets.save(ctx, list)
                // Games on the renamed/changed preset follow it (their tweaks are dropped: the preset changed).
                GameStore.all(ctx).filterValues { it.preset.key == d.key }.forEach { (id, _) -> GameStore.set(ctx, id, GameSettings.of(saved)) }
            }
            edit = null
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My presets", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Button(onClick = onDone, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Back") }
            }
        }
        if (list.isEmpty()) item { Text("None yet. Make one, or import a file a friend shared.", color = Hand.Muted, fontSize = 15.sp) }
        items(list, key = { it.key }) { d ->
            HandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(GameSettings.of(d).describe(), color = Hand.Muted, fontSize = 13.sp)
                    }
                    Button(onClick = { edit = d }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Edit") }
                    Button(onClick = {
                        list = list - d; CustomPresets.save(ctx, list)
                        note = "Deleted ${d.label}. Games using it keep their settings."
                    }, Modifier.padding(start = 8.dp).glowFocus(RoundedCornerShape(50))) { Text("Delete") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { edit = PresetDef("", "", Preset.BALANCED.pulse, Profile.NORMAL) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("New preset") }
                Button(onClick = { scope.launch(Dispatchers.IO) {
                    val ok = CustomPresets.export(ctx)
                    withContext(Dispatchers.Main) { note = if (ok) "Saved to Downloads/HandyTuner-presets.json (your presets and game settings)" else "Couldn't save the file" }
                } }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Export") }
                Button(onClick = { scope.launch(Dispatchers.IO) {
                    val inc = CustomPresets.import(ctx)
                    withContext(Dispatchers.Main) {
                        list = CustomPresets.all(ctx)
                        note = if (inc == null) "No readable Downloads/HandyTuner-presets.json" else
                            "Imported ${inc.presets.size} preset(s) and ${inc.games.size} new game(s)" +
                                (if (inc.skipped > 0) "; ${inc.skipped} skipped (values HandyTuner doesn't allow)" else "") +
                                ". Games you already have were left as they are."
                    }
                } }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Import") }
            }
        }
        note?.let { item { Text(it, color = Hand.Blue, fontSize = 14.sp) } }
    }
}

/** Make or change one preset: the same choices the Quick Menu's Pulse tile gives, plus HandyTuner's part. */
@Composable
private fun PresetForm(start: PresetDef, taken: List<String>, onDone: (PresetDef?) -> Unit) {
    var name by remember { mutableStateOf(start.label) }
    var p by remember { mutableStateOf(start.pulse) }
    var profile by remember { mutableStateOf(start.profile) }
    LazyColumn {
        item {
            OutlinedTextField(value = name, onValueChange = { name = it.take(CustomPresets.MAX_NAME) }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(0.6f))
            val tiers = listOf("Max", "Balanced", "Saver")
            chips("Mode", listOf("Auto" to (p.autoFps != null)) + tiers.mapIndexed { i, t -> t to (p.tier == i) }) { i ->
                p = if (i == 0) PulsePart(p.autoFps ?: 60, null, 0, p.fan) else PulsePart(null, i - 1, p.cap, p.fan)
            }
            if (p.autoFps != null) {
                val fps = listOf(30, 40, 60, 120)   // the Odin 2 Portal's AutoTDP targets (60/120 Hz panel: no 90)
                chips("AutoTDP target", fps.map { "$it fps" to (p.autoFps == it) }) { i -> p = p.copy(autoFps = fps[i]) }
            } else {
                val caps = listOf(0, 30, 40, 60)
                chips("Frame cap", caps.map { (if (it == 0) "No cap" else "Cap $it") to (p.cap == it) }) { i -> p = p.copy(cap = caps[i]) }
            }
            val fans = listOf(Actions.FAN_QUIET, Actions.FAN_SMART, Actions.FAN_SPORT, Actions.FAN_CUSTOM)
            chips("Fan", fans.map { Actions.FAN_NAMES.getValue(it) to (p.fan == it) }) { i -> p = p.copy(fan = fans[i]) }
            chips("HandyTuner", Profile.entries.map { it.label to (profile == it) }) { i -> profile = Profile.entries[i] }
            val clean = CustomPresets.cleanName(name)
            val final = CustomPresets.freeName(clean, taken)
            if (clean.isNotEmpty() && final != clean) Text("That name is taken; it will be saved as \"$final\".", color = Hand.Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onDone(PresetDef(PresetDef.CUSTOM + final, final, p, profile)) }, enabled = clean.isNotEmpty() && p.valid,
                    modifier = Modifier.glowFocus(RoundedCornerShape(50))) { Text("Save") }
                Button(onClick = { onDone(null) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Cancel") }
            }
        }
    }
}
