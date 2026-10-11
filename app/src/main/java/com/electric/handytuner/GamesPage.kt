// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every game and its preset (docs/architecture.md, UI spec "Games"). The :overlay process applies a game's
 * settings when it comes to the front; changes made here reach Pulse the next time that game opens.
 */
@Composable
fun GamesPage(ctx: Context) {
    var games by remember { mutableStateOf(GameStore.all(ctx)) }
    var customs by remember { mutableStateOf(CustomPresets.all(ctx)) }
    var editing by remember { mutableStateOf(false) }
    if (editing) { PresetsEditor(ctx) { customs = CustomPresets.all(ctx); games = GameStore.all(ctx); editing = false }; return }
    val presets = Preset.entries.map { it.def } + customs
    var picking by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }   // id to label, games first
    val sessions = remember { Sessions.all(ctx) }
    fun label(id: String) = GameId.label(id) ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(id, 0)).toString() }.getOrDefault(id)
    fun set(id: String, s: GameSettings?) { GameStore.set(ctx, id, s); games = GameStore.all(ctx) }

    LaunchedEffect(picking) {
        if (!picking) return@LaunchedEffect
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { it != ctx.packageName && it !in games }
                .map { it to label(it) }
                .sortedWith(compareBy({ !GameStore.isGame(ctx, it.first) }, { it.second.lowercase() }))
                // Games played recently come first: a Windows game from Cocoon only has a name once it has run.
                .let { list -> sessions.map { it.pkg }.distinct().filter { it !in games }.map { it to label(it) } + list.filter { a -> sessions.none { it.pkg == a.first } } }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column {
                Text("Each game gets its preset when it opens. Changes from the Quick Menu are saved to that game.",
                    color = Hand.Muted, fontSize = 14.sp)
                HandCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    presets.forEach { p ->
                        Text("${p.label}: ${GameSettings.of(p).describe()}", color = Hand.Muted, fontSize = 13.sp)
                    }
                    Text("New games start on ${Preset.NEW_GAME.label}.", color = Hand.Muted, fontSize = 13.sp)
                    Button(onClick = { editing = true }, Modifier.padding(top = 8.dp).glowFocus(RoundedCornerShape(50))) {
                        Text("My presets, export & import")
                    }
                }
            }
        }
        if (games.isEmpty()) item { Text("Play a game once and it shows up here.", color = Hand.Muted, fontSize = 15.sp) }
        items(games.entries.sortedBy { label(it.key).lowercase() }, key = { it.key }) { (id, s) ->
            // Name on its own line and the chips below: with custom presets a single row squeezed the name to one letter wide.
            HandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label(id), color = Hand.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                        Text(s.describe() + if (s.tweaked) "  (tweaked)" else "", color = Hand.Muted, fontSize = 13.sp, maxLines = 1)
                    }
                    Button(onClick = { set(id, null) }, Modifier.padding(start = 10.dp).glowFocus(RoundedCornerShape(50))) { Text("Remove") }
                }
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
                    presets.forEach { p ->
                        FilterChip(selected = s.preset.key == p.key && !s.tweaked, onClick = { set(id, GameSettings.of(p)) }, label = { Text(p.label) },
                            modifier = Modifier.padding(end = 6.dp).glowFocus(RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
        item {
            Button(onClick = { picking = !picking }, Modifier.glowFocus(RoundedCornerShape(50))) {
                Text(if (picking) "Done adding" else "Add a game")
            }
        }
        if (picking) items(apps, key = { "add-" + it.first }) { (id, name) ->
            HandCard(Modifier.fillMaxWidth().glowFocus().clickable { set(id, GameSettings.of(Preset.NEW_GAME)); apps = apps.filter { it.first != id } }) {
                Row {
                    Text(name, color = Hand.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (GameStore.isGame(ctx, id)) Text("game", color = Hand.Blue, fontSize = 13.sp)
                }
            }
        }
    }
}
