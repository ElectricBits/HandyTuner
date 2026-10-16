// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.delay

/*
 * Controller's drill-in screens (Setup.kt): what's connected, a button and stick test, and buttons given HandyTuner
 * actions. Plus the preset row Dock and Controller share.
 */

/** Pick a preset, or none ([none]: "the game's own"). [key] is a [PresetDef.key]. */
@Composable
fun PresetChoiceRow(ctx: Context, name: String, key: String?, none: String, icon: ImageVector? = null, hint: String? = null,
                    help: String? = null, onPick: (String?) -> Unit) {
    val options = remember { listOf<PresetDef?>(null) + SetupRules.presets(ctx) }
    ChoiceRow(name, options, options.indexOfFirst { it?.key == key }.coerceAtLeast(0), { it?.label ?: none },
        hint = hint, help = help, icon = icon) { onPick(options[it]?.key) }
}

/** Every controller Android sees, refreshed every two seconds: brand, battery, hints, and a rumble test. */
@Composable
fun ConnectedPadsScreen(ctx: Context) {
    var rules by remember { mutableStateOf(SetupRules.load(ctx)) }
    var devices by remember { mutableStateOf(emptyList<InputDevice>()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { devices = Pads.allGamepads(); delay(2_000) } }
    fun save(r: SetupRules) { rules = r; SetupRules.save(ctx, r) }

    ConsolePage {
        Note("External controllers (Bluetooth, USB or a dongle) and the Odin's own. Pair a new one in Android's Bluetooth settings.")
        if (devices.isEmpty()) Note("No controllers found.")
        devices.forEach { d ->
            key(d.id) {
                val ignored = d.name in rules.ignoredPads
                val external = Pads.isExternal(d, rules.ignoredPads)
                val brand = Pads.brand(d.vendorId, d.name)
                val battery = runCatching { d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() } }.getOrNull()
                RowGroup(d.name) {
                    InfoRow(when { ignored -> "Marked as not a controller"; external -> "External controller"; else -> "The Odin's own controls" },
                        listOfNotNull(brand, battery?.let { "battery ${Math.round(it * 100)}%" }).joinToString(" · "),
                        hint = (if (external) Pads.hint(brand) else null) ?: "id %04x:%04x".format(d.vendorId, d.productId),
                        valueColor = if (external) Hand.Blue else null)
                    ActionRow("Rumble test", hint = note?.takeIf { it.contains(d.name) }, icon = Icons.Rounded.Vibration) {
                        note = if (Pads.rumble(d.id)) "Rumbling ${d.name}…" else "${d.name} has no rumble Android can use."
                    }
                    // The safety valve: if the Odin's own controls are ever taken for an external pad, this stops
                    // HandyTuner switching to controller mode because of them.
                    if (external || ignored) ActionRow(if (ignored) "It is a controller after all" else "This isn't a controller",
                        hint = if (ignored) "Undo" else "Stops HandyTuner treating it as one", icon = Icons.Rounded.SportsEsports) {
                        save(rules.copy(ignoredPads = if (ignored) rules.ignoredPads - d.name else rules.ignoredPads + d.name))
                    }
                }
            }
        }
    }
}

/** Live: every button press with the code it sends, and where the sticks and triggers are. */
@Composable
fun ButtonTestScreen(ctx: Context) {
    val act = ctx as? MainActivity ?: return
    val presses = remember { mutableStateListOf<String>() }
    var axes by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var from by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        act.keyHook = { e ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Pads.isPadKey(e.keyCode)) {
                presses.add(0, "${Hotkey.buttonName(e.keyCode)} (${e.keyCode}) · ${e.device?.name ?: "?"}")
                while (presses.size > 6) presses.removeAt(presses.lastIndex)
            }
            false
        }
        act.motionHook = { e ->
            if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
                fun a(axis: Int) = e.getAxisValue(axis)
                axes = mapOf(
                    "Left stick" to a(MotionEvent.AXIS_X), "Left stick ↕" to a(MotionEvent.AXIS_Y),
                    "Right stick" to a(MotionEvent.AXIS_Z), "Right stick ↕" to a(MotionEvent.AXIS_RZ),
                    "L2" to maxOf(a(MotionEvent.AXIS_LTRIGGER), a(MotionEvent.AXIS_BRAKE)),
                    "R2" to maxOf(a(MotionEvent.AXIS_RTRIGGER), a(MotionEvent.AXIS_GAS)),
                )
                from = e.device?.name
            }
        }
        onDispose { act.keyHook = null; act.motionHook = null }
    }
    ConsolePage {
        RowGroup {
            InfoRow("Press any button", presses.firstOrNull() ?: "waiting…", hint = "Back paddles too. B leaves the test.")
        }
        if (presses.size > 1) RowGroup("Before that") {
            Panel { presses.drop(1).forEach { Text(it, color = Hand.Muted, fontFamily = Hand.Mono, fontSize = 14.sp) } }
        }
        RowGroup("Sticks${from?.let { " · $it" } ?: ""}") {
            Panel {
                if (axes.isEmpty()) Text("Move a stick.", color = Hand.Muted, fontSize = 14.sp)
                axes.entries.chunked(2).forEach { row ->
                    Text(row.joinToString("     ") { (k, v) -> "$k ${"%+.2f".format(v)}" }, fontFamily = Hand.Mono, fontSize = 15.sp,
                        color = if (row.any { kotlin.math.abs(it.value) > 0.1f && !it.key.startsWith("L2") && !it.key.startsWith("R2") }) Hand.Warn else Hand.Text,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
                Text("With the sticks let go they should read close to 0.00; more than 0.10 at rest means drift.", color = Hand.Muted,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Buttons that do a HandyTuner action instead of reaching the game: back paddles are the obvious ones. */
@Composable
fun PadShortcutsScreen(ctx: Context) {
    val act = ctx as? MainActivity ?: return
    var map by remember { mutableStateOf(PadAction.load(ctx)) }
    var listening by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val addRow = remember { FocusRequester() }
    // Rows below "Add a shortcut" come and go; hand focus to it first so the D-pad never loses its row.
    fun save(m: Map<Int, PadAction>) { runCatching { addRow.requestFocus() }; map = m; PadAction.save(ctx, m) }
    val hotkeyButtons = remember { Hotkey.HUD.load(ctx) + Hotkey.MENU.load(ctx) }

    DisposableEffect(listening) {
        if (!listening) return@DisposableEffect onDispose { }
        val before = act.keyHook
        // The first button pressed is the one: recording stops there. Stick movement is ignored, and nothing
        // reaches the screen meanwhile (a stick click would press whatever has focus).
        act.keyHook = { e ->
            if (e.action == KeyEvent.ACTION_DOWN && Pads.isRecordable(e.keyCode, e.flags)) { picked = e.keyCode; listening = false }
            true
        }
        // Always put the old hook back. (This used to check `listening` here, which is already false by then, so
        // the hook stayed and swallowed every button, B included, until the page closed.)
        onDispose { act.keyHook = before }
    }
    LaunchedEffect(listening) { if (listening) { delay(10_000); listening = false } }

    ConsolePage {
        Note("In games, a shortcut button does its action and the game doesn't see it. Works for any controller, the Odin's own too.")
        RowGroup {
            val p = picked
            ActionRow("Add a shortcut", requester = addRow, icon = Icons.Rounded.AddCircle,
                hint = when { listening -> "Press the button now…"; p != null -> "${Hotkey.buttonName(p)}: pick what it does below"; else -> "Then press the button to use" }) {
                picked = null; listening = true
            }
            if (p != null) {
                if (p in hotkeyButtons) Note("This button is part of a hotkey. An action keeps it from the game, but the hotkey still works.", Hand.Warn)
                PadAction.entries.forEach { a -> ActionRow(a.label, icon = Icons.Rounded.TouchApp) { picked = null; save(map + (p to a)) } }
                ActionRow("Cancel", icon = Icons.Rounded.Close) { runCatching { addRow.requestFocus() }; picked = null }
            }
        }
        if (map.isNotEmpty()) RowGroup("Your shortcuts") {
            map.forEach { (code, a) ->
                ActionRow("${Hotkey.buttonName(code)} → ${a.label}", value = "Remove", confirm = "remove it") { save(map - code) }
            }
        }
    }
}
