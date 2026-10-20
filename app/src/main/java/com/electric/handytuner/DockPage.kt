// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/** How the Odin behaves docked: what counts as docked, presets, apps, the screens, and charging. */
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
                if (screens > 0) "TV or monitor" else "no TV or monitor",
                if (Dock.plugged(ctx)) "charging" else "on battery",
                pads.firstOrNull()?.name?.let { "controller: $it" } ?: "no controller",
            ).joinToString(" · ")
            delay(2_000)
        }
    }

    ConsolePage {
        RowGroup("Right now") {
            InfoRow("${now?.first?.label ?: "…"} mode", "", hint = now?.second ?: "Checking…")
            ToggleRow("Count any charger as docked", r.chargerIsDock, hint = "So a desk stand without a TV counts too",
                help = "Docked normally means a TV or monitor is plugged in. Docked with a controller is Couch mode.",
                icon = Icons.Rounded.Cable) { save(r.copy(chargerIsDock = it)) }
        }

        RowGroup("Presets while docked") {
            PresetChoiceRow(ctx, "Docked", r.dockedPreset, "Each game's own", icon = Icons.Rounded.Tv,
                hint = "Goes over each game's own preset",
                help = "The game's own comes back when you undock. A game you change in the Quick Menu keeps your change. " +
                    "Docked you're on power, so Performance is a good pick.") { save(r.copy(dockedPreset = it)) }
            PresetChoiceRow(ctx, "Couch", r.couchPreset, "Same as docked", icon = Icons.Rounded.Weekend,
                hint = "Docked with a controller connected") { save(r.copy(couchPreset = it)) }
            val apps = remember { launchable(ctx) }
            val options = listOf<Pair<String?, String>>(null to "Nothing") + apps
            ChoiceRow("Open when docked", options, options.indexOfFirst { it.first == r.launchOnDock }.coerceAtLeast(0), { it.second },
                hint = if (apps.isEmpty()) "Play a game once and it shows up here" else "When you dock, not when it starts up docked",
                icon = Icons.Rounded.Launch) { save(r.copy(launchOnDock = options[it].first)) }
        }

        ResolutionGroup(ctx)

        RowGroup("While docked") {
            ToggleRow("TV-size HUD", r.tvHud, hint = "A bigger HUD, readable from the sofa", icon = Icons.Rounded.Insights) { save(r.copy(tvHud = it)) }
            ToggleRow("Dim the Odin's screen", r.dimScreen, hint = "The TV keeps its picture", icon = Icons.Rounded.BrightnessLow,
                help = "Brightness comes back when you undock.") { save(r.copy(dimScreen = it)) }
            ToggleRow("Keep the screen awake", r.keepAwake, hint = "No screen timeout while docked", icon = Icons.Rounded.LightMode) { save(r.copy(keepAwake = it)) }
            ToggleRow("Pause sleep underclock", r.sleepOff, hint = "Back on when you undock", icon = Icons.Rounded.Bedtime) { save(r.copy(sleepOff = it)) }
        }

        DockChargeGroup(ctx)
    }
}

/** Fewer pixels: less work for the graphics chip, and on a 4K TV about 300 MB less memory. */
@Composable
private fun ResolutionGroup(ctx: Context) {
    var r by remember { mutableStateOf(Resolution.load(ctx)) }
    // The Odin's own lines: its short side (1080 on the Odin 2 Portal), so each choice can say what it is.
    val lines = remember { ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode.let { minOf(it.physicalWidth, it.physicalHeight) } }
    fun save(n: Resolution, screen: Boolean, tv: Boolean) {
        r = n; Resolution.save(ctx, n)
        thread { if (screen) Resolution.applyScreen(n); if (tv) Resolution.applyTv(ctx, n) }
    }
    val why = "Fewer pixels: less work for the graphics chip, and on a 4K TV about 300 MB less memory. A running game may " +
        "pause once when it changes. Reset everything to stock puts both back."
    RowGroup("Resolution") {
        ChoiceRow("The Odin's screen", Resolution.SCALES, Resolution.SCALES.indexOf(r.screenPct),
            { p -> "${if (p == 100) "Native · " else "$p% · "}${lines * p / 100}p" }, hint = "Lower is lighter on the chip", help = why,
            icon = Icons.Rounded.PhoneAndroid) { i -> save(r.copy(screenPct = Resolution.SCALES[i]), screen = true, tv = false) }
        ChoiceRow("The TV, while docked", Resolution.TV_HEIGHTS, Resolution.TV_HEIGHTS.indexOf(r.tvHeight),
            { h -> when (h) { 0 -> "Default"; 2160 -> "4K"; else -> "${h}p" } }, hint = "Default is what the TV asks for", help = why,
            icon = Icons.Rounded.Tv) { i -> save(r.copy(tvHeight = Resolution.TV_HEIGHTS[i]), screen = false, tv = true) }
        ToggleRow("Keep text and buttons the same size", r.keepSize, hint = "Off: everything gets bigger at a lower resolution",
            icon = Icons.Rounded.TextFields) { save(r.copy(keepSize = it), screen = true, tv = true) }
    }
}

/** The charge limit while docked, on top of the Battery page's. Saving turns charge control on, as the Battery page does. */
@Composable
private fun DockChargeGroup(ctx: Context) {
    var s by remember { mutableStateOf(ChargeRule.load(ctx)) }
    fun set(n: ChargeRule.Settings) { s = n; ChargeRule.save(ctx, n) }
    RowGroup("Charging while docked") {
        ToggleRow("Own limit while docked", s.dockedLimit != null,
            hint = "Off: the Battery page's (${if (s.limit >= 100) "no limit" else "${s.limit}%"})",
            help = "A docked Odin sits on the charger for hours, and a battery kept full wears faster.",
            icon = Icons.Rounded.BatteryChargingFull) { set(s.copy(dockedLimit = if (it) 80 else null)) }
        s.dockedLimit?.let { lim ->
            SliderRow("Docked limit", lim, 50..100, 5, { if (it >= 100) "No limit" else "$it%" }, icon = Icons.Rounded.BatteryChargingFull) {
                set(s.copy(dockedLimit = it))
            }
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
