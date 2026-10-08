// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Dock & TV: what changes while the Odin is docked (Setup.kt). The :overlay watcher does the switching;
 * this page only writes setup.properties and the docked charge limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DockPage(ctx: Context) {
    var r by remember { mutableStateOf(SetupRules.load(ctx)) }
    fun save(n: SetupRules) { r = n; SetupRules.save(ctx, n) }

    // What HandyTuner sees right now, refreshed every two seconds.
    var now by remember { mutableStateOf<Pair<Setup, String>?>(null) }
    LaunchedEffect(r) {
        while (true) {
            val screens = Dock.externalDisplays(ctx)
            val pads = Pads.connected(r.ignoredPads)
            val setup = Setup.of(Dock.docked(ctx, r), pads.isNotEmpty())
            now = setup to listOfNotNull(
                if (screens > 0) "TV or monitor connected" else "no TV or monitor",
                if (Dock.plugged(ctx)) "charging" else "on battery",
                pads.firstOrNull()?.name?.let { "controller: $it" } ?: "no controller",
            ).joinToString(" · ")
            delay(2_000)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HandCard(Modifier.fillMaxWidth()) {
            Text("Right now: ${now?.first?.label ?: "…"} mode", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(now?.second ?: "Checking…", color = Hand.Blue, fontSize = 14.sp)
            Text("Docked means a TV or monitor is plugged in. Docked with a controller is Couch mode.", color = Hand.Muted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
            SettingSwitch("Count any charger as docked", "For a dock without a TV, so a desk stand counts too.", r.chargerIsDock) {
                save(r.copy(chargerIsDock = it))
            }
        }

        HandCard(Modifier.fillMaxWidth()) {
            Text("Presets", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text("Goes over each game's own preset while docked, and the game's own comes back when you undock. Docked, " +
                "you're on power, so Performance is a good pick.", color = Hand.Muted, fontSize = 14.sp)
            PresetPicker("Docked", r.dockedPreset, "Each game's own", ctx) { save(r.copy(dockedPreset = it)) }
            PresetPicker("Couch (docked + controller)", r.couchPreset, "Same as docked", ctx) { save(r.copy(couchPreset = it)) }
        }

        HandCard(Modifier.fillMaxWidth()) {
            Text("While docked", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            SettingSwitch("TV-size HUD", "A bigger HUD, readable from the sofa.", r.tvHud) { save(r.copy(tvHud = it)) }
            SettingSwitch("Dim the Odin's screen", "The TV keeps its picture. Brightness comes back when you undock.", r.dimScreen) {
                save(r.copy(dimScreen = it))
            }
            SettingSwitch("Keep the screen awake", "No screen timeout while docked.", r.keepAwake) { save(r.copy(keepAwake = it)) }
            SettingSwitch("Pause sleep underclock", "PULSE's sleep underclock stays off while docked, and comes back on when you undock.",
                r.sleepOff) { save(r.copy(sleepOff = it)) }
        }

        ResolutionCard(ctx)

        DockChargeCard(ctx)

        HandCard(Modifier.fillMaxWidth()) {
            Text("Open an app when docked", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text("Opens when you dock, not when the Odin starts up already docked.", color = Hand.Muted, fontSize = 14.sp)
            val apps = remember { launchable(ctx) }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = r.launchOnDock == null, onClick = { save(r.copy(launchOnDock = null)) }, label = { Text("Nothing") },
                    modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
                apps.forEach { (pkg, label) ->
                    FilterChip(selected = r.launchOnDock == pkg, onClick = { save(r.copy(launchOnDock = pkg)) }, label = { Text(label) },
                        modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
                }
            }
            if (apps.isEmpty()) Text("Play a game once and it shows up here.", color = Hand.Muted, fontSize = 13.sp)
        }
    }
}

/** The charge limit while docked, on top of the Battery page's. Saving turns charge control on, as the Battery page does. */
@Composable
private fun DockChargeCard(ctx: Context) {
    var s by remember { mutableStateOf(ChargeRule.load(ctx)) }
    fun set(n: ChargeRule.Settings) { s = n; ChargeRule.save(ctx, n) }
    HandCard(Modifier.fillMaxWidth()) {
        Text("Charging while docked", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("A docked Odin sits on the charger for hours, and a battery kept full wears faster.", color = Hand.Muted, fontSize = 14.sp)
        SettingSwitch("Own limit while docked", "Off: the Battery page's limit (${if (s.limit >= 100) "no limit" else "${s.limit}%"}).",
            s.dockedLimit != null) { set(s.copy(dockedLimit = if (it) 80 else null)) }
        s.dockedLimit?.let { lim ->
            Text(if (lim >= 100) "No limit" else "Stop at $lim%", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Slider(value = lim.toFloat(), onValueChange = { set(s.copy(dockedLimit = (it / 5).roundToInt() * 5)) }, valueRange = 50f..100f, steps = 9)
        }
    }
}

/** Games HandyTuner has seen, and installed launchers and emulators: the apps worth opening on a dock. */
private fun launchable(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    return (GameStore.all(ctx).keys.filter { ':' !in it } + GameStore.LAUNCHERS).distinct()
        .filter { pm.getLaunchIntentForPackage(it) != null }
        .map { it to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
        .sortedBy { it.second.lowercase() }
}
