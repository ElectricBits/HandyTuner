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

/** The Odin's button layout, connected controllers and their tools, what changes with one connected. */
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
                icon = Icons.Rounded.Tune, value = "Open") { launch("com.odin.factorytest", "com.ro.factorytest.itemstest.GamepadTestActivity") }
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
            ToggleRow("Controller alerts", r.padAlerts, hint = "When it disconnects mid-game or its battery is low",
                icon = Icons.Rounded.BatteryAlert) { save(r.copy(padAlerts = it)) }
        }

    }
}
