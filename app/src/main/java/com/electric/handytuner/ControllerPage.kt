// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.Shortcut
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Odin's button layout, connected controllers and their tools, what changes with one connected, and key mapping. */
@Composable
fun ControllerPage(ctx: Context) {
    val scope = rememberCoroutineScope()
    val open = openSub()
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
    fun launch(pkg: String, cls: String) = runCatching {
        ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    var r by remember { mutableStateOf(SetupRules.load(ctx)) }
    fun save(n: SetupRules) { r = n; SetupRules.save(ctx, n) }

    ConsolePage {
        RowGroup("The Odin's buttons") {
            val layouts = listOf(1 to "Xbox (A bottom)", 0 to "Swapped (A right)")
            ChoiceRow("Face buttons", layouts, layouts.indexOfFirst { it.first == flipped }, { it.second },
                hint = "Which face button is A", help = "Try it in the Key Test below.", icon = Icons.Rounded.Gamepad) { setLayout(layouts[it].first) }
            // What the Odin 2 Portal's firmware has there (2026-10-08); it has no deadzone or invert options.
            ActionRow("Odin's controller settings", hint = "Controller style, L2/R2 mode, what the paddles and Back do",
                icon = Icons.Rounded.SettingsApplications, value = "Open") { launch("com.odin.settings", "com.ro.settings.activity.MainSettingsActivity") }
            ActionRow("Key Test & stick calibration", hint = "AYN's test screen: every press, and drifting sticks",
                icon = Icons.Rounded.Tune, value = "Open") { launch("com.odin.gameassistant", "com.ro.gameassistant.activity.GamepadTestActivity") }
        }

        StickLightsGroup(ctx)

        RowGroup("Controllers") {
            NavRow("Connected controllers", hint = "Brand, battery, rumble test", icon = Icons.Rounded.SportsEsports,
                value = Pads.allGamepads().size.toString()) { from -> open("Connected controllers", from) { ConnectedPadsScreen(ctx) } }
            NavRow("Button & stick test", hint = "See what every button sends, and check for drift", icon = Icons.Rounded.TouchApp) { from ->
                open("Button & stick test", from) { ButtonTestScreen(ctx) }
            }
            NavRow("Button shortcuts", hint = "Give a button a HandyTuner action", icon = Icons.Rounded.Shortcut,
                value = PadAction.load(ctx).size.takeIf { it > 0 }?.toString()) { from -> open("Button shortcuts", from) { PadShortcutsScreen(ctx) } }
        }

        RowGroup("With a controller connected") {
            PresetChoiceRow(ctx, "Preset", r.padPreset, "Each game's own", icon = Icons.Rounded.Layers,
                hint = "For every game while a controller is connected",
                help = "HandyTuner notices a controller within a few seconds and switches back when it's gone. A game you change " +
                    "in the Quick Menu keeps your change. Docked with a controller is Couch mode, set in Dock.") { save(r.copy(padPreset = it)) }
            ToggleRow("Ignore the Odin's own buttons", r.ignoreBuiltIn, hint = "So a bump doesn't press anything",
                help = "Buttons only: Android doesn't let HandyTuner hold back the sticks. The hotkeys still work.",
                icon = Icons.Rounded.VisibilityOff) { save(r.copy(ignoreBuiltIn = it)) }
            ToggleRow("Hide key-mapping markers", r.hideMarkers, hint = "The dots that show where mapped buttons tap",
                icon = Icons.Rounded.TouchApp) { save(r.copy(hideMarkers = it)) }
            ToggleRow("Controller alerts", r.padAlerts, hint = "When it disconnects mid-game or its battery is low",
                icon = Icons.Rounded.BatteryAlert) { save(r.copy(padAlerts = it)) }
        }

        KeyMappingGroup(ctx)
    }
}

/** Key mapping: buttons to screen taps for touch games. Layouts are made in the Quick Menu; here is how they behave. */
@Composable
private fun KeyMappingGroup(ctx: Context) {
    var set by remember { mutableStateOf(KeyMapper.settings(ctx)) }
    fun save(n: KeyMapper.Settings) { set = n; KeyMapper.saveSettings(ctx, n) }
    val open = openSub()
    RowGroup("Key mapping") {
        NavRow("Saved layouts", hint = "Per game; export and import", icon = Icons.Rounded.Keyboard,
            value = KeyMapper.all(ctx).size.toString()) { from -> open("Saved layouts", from) { LayoutsScreen(ctx) } }
        ToggleRow("Show the layout in games", set.show, hint = "Markers where mapped buttons tap",
            help = "Make a layout from the Quick Menu while the game is open: press a placed button again to switch tap → hold → " +
                "auto-fire. A layout made with an external controller connected is saved for that controller.",
            icon = Icons.Rounded.TouchApp) { save(set.copy(show = it)) }
        SliderRow("See-through", set.opacity, 10..90, 5, { "$it%" }, hint = "How see-through the markers are", icon = Icons.Rounded.Opacity) {
            save(set.copy(opacity = it))
        }
        SliderRow("Tap length", set.tapMs, 20..200, 10, { "$it ms" }, hint = "How long each tap holds", icon = Icons.Rounded.Timer) {
            save(set.copy(tapMs = it))
        }
        SliderRow("Auto-fire speed", set.autoRate, 2..20, 1, { "$it/s" }, hint = "Taps per second on auto-fire", icon = Icons.Rounded.Whatshot) {
            save(set.copy(autoRate = it))
        }
    }
}

/** Each game's layout (A twice deletes it), and the layouts as one file in Downloads. */
@Composable
private fun LayoutsScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var maps by remember { mutableStateOf(KeyMapper.all(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    fun label(pkg: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    ConsolePage {
        RowGroup {
            ActionRow("Export", hint = note ?: "To Downloads/HandyTuner-keymaps.json", enabled = maps.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val ok = KeyMapper.exportAll(ctx)
                    withContext(Dispatchers.Main) { note = if (ok) "Saved to Downloads/HandyTuner-keymaps.json" else "Couldn't save the file" }
                }
            }
            ActionRow("Import", hint = "From Downloads/HandyTuner-keymaps.json") {
                scope.launch(Dispatchers.IO) {
                    val n = KeyMapper.importAll(ctx)
                    withContext(Dispatchers.Main) {
                        maps = KeyMapper.all(ctx)
                        note = if (n < 0) "No Downloads/HandyTuner-keymaps.json to import" else "Imported $n game layout${if (n == 1) "" else "s"}"
                    }
                }
            }
        }
        RowGroup("Layouts") {
            if (maps.isEmpty()) InfoRow("No layouts yet", "", hint = "Make one from the Quick Menu while a game is open", focusable = false)
            maps.forEach { (pkg, spots) ->
                val pad = pkg.endsWith(KeyMapper.PAD)
                ActionRow(label(pkg.removeSuffix(KeyMapper.PAD)) + if (pad) " (controller)" else "",
                    hint = "${spots.size} button${if (spots.size == 1) "" else "s"}", value = "Delete", confirm = "delete it") {
                    KeyMapper.set(ctx, pkg, emptyList()); maps = KeyMapper.all(ctx)
                }
            }
        }
    }
}
