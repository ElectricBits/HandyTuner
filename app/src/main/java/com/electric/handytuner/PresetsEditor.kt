// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        PresetForm(d, isNew = list.none { it.key == d.key }, taken = list.filter { it.key != d.key }.map { it.label }) { saved, delete ->
            when {
                delete -> {
                    list = list - d; CustomPresets.save(ctx, list)
                    note = "Deleted ${d.label}. Games using it keep their settings."
                }
                saved != null -> {
                    list = list.filter { it.key != d.key } + saved
                    CustomPresets.save(ctx, list)
                    // Games on the renamed/changed preset follow it (their tweaks are dropped: the preset changed).
                    GameStore.all(ctx).filterValues { it.preset.key == d.key }.forEach { (id, _) -> GameStore.set(ctx, id, GameSettings.of(saved)) }
                }
            }
            edit = null
        }
        return
    }

    ConsolePage {
        note?.let { Note(it, Hand.Blue) }
        RowGroup("Your presets") {
            if (list.isEmpty()) InfoRow("None yet", "", hint = "Make one, or import a file a friend shared", focusable = false)
            list.forEach { d -> ActionRow(d.label, hint = GameSettings.of(d).describe(), value = "Edit") { edit = d } }
        }
        RowGroup("Share") {
            ActionRow("New preset", hint = "Starts from Balanced", icon = Icons.Rounded.AddCircle) {
                edit = PresetDef("", "", Preset.BALANCED.pulse, Profile.NORMAL)
            }
            ActionRow("Export", hint = "Your presets and game settings, to Downloads", icon = Icons.Rounded.Upload) {
                scope.launch(Dispatchers.IO) {
                    val ok = CustomPresets.export(ctx)
                    withContext(Dispatchers.Main) { note = if (ok) "Saved to Downloads/HandyTuner-presets.json (your presets and game settings)" else "Couldn't save the file" }
                }
            }
            ActionRow("Import", hint = "From Downloads/HandyTuner-presets.json", icon = Icons.Rounded.Download) {
                scope.launch(Dispatchers.IO) {
                    val inc = CustomPresets.import(ctx)
                    withContext(Dispatchers.Main) {
                        list = CustomPresets.all(ctx)
                        note = if (inc == null) "No readable Downloads/HandyTuner-presets.json" else
                            "Imported ${inc.presets.size} preset(s) and ${inc.games.size} new game(s)" +
                                (if (inc.skipped > 0) "; ${inc.skipped} skipped (values HandyTuner doesn't allow)" else "") +
                                ". Games you already have were left as they are."
                    }
                }
            }
        }
    }
}

/** Make or change one preset: the same choices the Quick Menu's Pulse tile gives, plus HandyTuner's part. */
@Composable
private fun PresetForm(start: PresetDef, isNew: Boolean, taken: List<String>, onDone: (saved: PresetDef?, delete: Boolean) -> Unit) {
    var name by remember { mutableStateOf(start.label) }
    var p by remember { mutableStateOf(start.pulse) }
    var profile by remember { mutableStateOf(start.profile) }
    val clean = CustomPresets.cleanName(name)
    val final = CustomPresets.freeName(clean, taken)
    ConsolePage {
        RowGroup(if (isNew) "New preset" else "Edit ${start.label}") {
            Panel {
                OutlinedTextField(value = name, onValueChange = { name = it.take(CustomPresets.MAX_NAME) }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (clean.isNotEmpty() && final != clean) Text("That name is taken; it will be saved as \"$final\".", color = Hand.Muted)
            }
            val modes = listOf("Auto", "Max", "Balanced", "Saver")
            ChoiceRow("Mode", modes, if (p.autoFps != null) 0 else (p.tier ?: 1) + 1, { it }, hint = "Auto holds an FPS target; the rest are fixed") { i ->
                p = if (i == 0) PulsePart(p.autoFps ?: 60, null, 0, p.fan) else PulsePart(null, i - 1, p.cap, p.fan)
            }
            if (p.autoFps != null) {
                val fps = listOf(30, 40, 60, 120)   // the Odin 2 Portal's AutoTDP targets (60/120 Hz panel: no 90)
                ChoiceRow("AutoTDP target", fps, fps.indexOf(p.autoFps), { "$it fps" }) { i -> p = p.copy(autoFps = fps[i]) }
            } else {
                val caps = listOf(0, 30, 40, 60)
                ChoiceRow("Frame cap", caps, caps.indexOf(p.cap), { if (it == 0) "No cap" else "Cap $it" }) { i -> p = p.copy(cap = caps[i]) }
            }
            val fans = listOf(Actions.FAN_QUIET, Actions.FAN_SMART, Actions.FAN_SPORT, Actions.FAN_CUSTOM)
            ChoiceRow("Fan", fans, fans.indexOf(p.fan), { Actions.FAN_NAMES.getValue(it) }) { i -> p = p.copy(fan = fans[i]) }
            ChoiceRow("HandyTuner", Profile.entries, Profile.entries.indexOf(profile), { it.label }, hint = profile.what) { i -> profile = Profile.entries[i] }
        }
        RowGroup {
            ActionRow("Save", hint = if (clean.isEmpty()) "Give it a name first" else null, enabled = clean.isNotEmpty() && p.valid) {
                onDone(PresetDef(PresetDef.CUSTOM + final, final, p, profile), false)
            }
            ActionRow("Cancel") { onDone(null, false) }
            if (!isNew) ActionRow("Delete preset", danger = true, confirm = "delete it", hint = "Games using it keep their settings") { onDone(null, true) }
        }
    }
}
