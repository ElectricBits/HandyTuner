// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every game and its preset (docs/architecture.md, UI spec "Games"). The :overlay process applies a game's
 * settings when it comes to the front; changes made here reach Pulse the next time that game opens.
 * One row per game: ◀▶ picks its preset, Y (twice) removes it.
 */
@Composable
fun GamesPage(ctx: Context) {
    var games by remember { mutableStateOf(GameStore.all(ctx)) }
    val customs = remember(games) { CustomPresets.all(ctx) }
    val presets = Preset.entries.map { it.def } + customs
    val open = openSub()
    fun label(id: String) = GameId.label(id) ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(id, 0)).toString() }.getOrDefault(id)
    fun set(id: String, s: GameSettings?) { GameStore.set(ctx, id, s); games = GameStore.all(ctx) }

    ConsolePage {
        RowGroup("Your games") {
            if (games.isEmpty()) InfoRow("No games yet", "", hint = "Play a game once and it shows up here", focusable = false)
            games.entries.sortedBy { label(it.key).lowercase() }.forEach { (id, s) ->
                var arming by remember(id) { mutableStateOf(false) }
                ChoiceRow(label(id), presets, presets.indexOfFirst { it.key == s.preset.key },
                    { p -> if (s.tweaked && p.key == s.preset.key) "${p.label} (tweaked)" else p.label },
                    hint = if (arming) "Press Ⓨ again to remove it" else s.describe(),
                    help = if (arming) "It goes back to the preset new games get if you play it again." else if (s.tweaked)
                        "Tweaked: you changed it in the Quick Menu. Pick the preset again to start fresh." else null,
                    hintColor = if (arming) Hand.Warn else null, kind = RowKind.GAME,
                    onFocus = { if (!it) arming = false },
                    onKey = { e ->
                        if (e.key != Key.ButtonY) false
                        else { if (e.type == KeyEventType.KeyDown) { if (arming) { arming = false; set(id, null) } else arming = true }; true }
                    }) { i -> arming = false; set(id, GameSettings.of(presets[i])) }
            }
        }
        RowGroup("More") {
            NavRow("Add a game", hint = "Give an app a preset before you play it", icon = Icons.Rounded.AddCircle) { from ->
                open("Add a game", from) { AddGame(ctx, games.keys) { id -> set(id, GameSettings.of(Preset.NEW_GAME)) } }
            }
            NavRow("My presets, export & import", hint = "Make your own presets, share them as a file", icon = Icons.Rounded.Tune) { from ->
                open("My presets", from) { val close = LocalCloseSub.current; PresetsEditor(ctx) { games = GameStore.all(ctx); close() } }
            }
            NavRow("What the presets do", hint = "New games start on ${Preset.NEW_GAME.label}", icon = Icons.Rounded.Info) { from ->
                open("What the presets do", from) {
                    ConsolePage { RowGroup { presets.forEach { p -> InfoRow(p.label, "", hint = GameSettings.of(p).describe()) } } }
                }
            }
        }
    }
}

/** Installed apps not on the list yet, recently played and games first; A adds one on the new-game preset. */
@Composable
private fun AddGame(ctx: Context, have: Set<String>, add: (String) -> Unit) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    fun label(id: String) = GameId.label(id) ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(id, 0)).toString() }.getOrDefault(id)
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val sessions = Sessions.all(ctx)
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { it != ctx.packageName && it !in have }
                .map { it to label(it) }
                .sortedWith(compareBy({ !GameStore.isGame(ctx, it.first) }, { it.second.lowercase() }))
                // Games played recently come first: a Windows game from Cocoon only has a name once it has run.
                .let { list -> sessions.map { it.pkg }.distinct().filter { it !in have }.map { it to label(it) } + list.filter { a -> sessions.none { it.pkg == a.first } } }
        }
    }
    ConsolePage {
        val list = apps ?: run { Note("Finding your apps…"); return@ConsolePage }
        var added by remember { mutableStateOf(emptySet<String>()) }
        RowGroup {
            list.filter { it.first !in added }.forEach { (id, name) ->
                ActionRow(name, hint = if (GameStore.isGame(ctx, id)) "Game" else null, value = "Add") { add(id); added = added + id }
            }
        }
    }
}
