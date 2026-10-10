// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * The Controller page's cards for external controllers (Setup.kt): what's connected, a button and stick
 * test, buttons given HandyTuner actions, and what changes while a controller is connected.
 */

@Composable
private fun CardTitle(name: String, what: String) {
    Text(name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    Text(what, color = Hand.Muted, fontSize = 14.sp)
}

/** A switch with its name and what it does. */
@Composable
fun SettingSwitch(name: String, what: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 16.sp)
            what?.let { Text(it, color = Hand.Muted, fontSize = 13.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.glowFocus(RoundedCornerShape(50)))
    }
}

/** Pick a preset, or none ("the game's own"). [key] is a [PresetDef.key]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetPicker(title: String, key: String?, none: String, ctx: Context, onPick: (String?) -> Unit) {
    val presets = remember { SetupRules.presets(ctx) }
    Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = key == null, onClick = { onPick(null) }, label = { Text(none) },
            modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
        presets.forEach { p ->
            FilterChip(selected = key == p.key, onClick = { onPick(p.key) }, label = { Text(p.label) },
                modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
        }
    }
}

/** Every controller Android sees, refreshed every two seconds: brand, battery, hints, and a rumble test. */
@Composable
fun ConnectedPadsCard(ctx: Context) {
    var rules by remember { mutableStateOf(SetupRules.load(ctx)) }
    var devices by remember { mutableStateOf(emptyList<InputDevice>()) }
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { devices = Pads.allGamepads(); tick++; delay(2_000) } }
    fun save(r: SetupRules) { rules = r; SetupRules.save(ctx, r) }

    HandCard(Modifier.fillMaxWidth()) {
        CardTitle("Connected controllers", "External controllers (Bluetooth, USB or a dongle) and the Odin's own. Pair a new one in " +
            "Android's Bluetooth settings.")
        if (devices.isEmpty()) Text("No controllers found.", color = Hand.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        devices.forEach { d ->
            val ignored = d.name in rules.ignoredPads
            val external = Pads.isExternal(d, rules.ignoredPads)
            val brand = Pads.brand(d.vendorId, d.name)
            val battery = runCatching { d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() } }.getOrNull()
            Column(Modifier.padding(top = 12.dp)) {
                Text(d.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(listOfNotNull(
                    when { ignored -> "Marked as not a controller"; external -> "External controller"; else -> "The Odin's own controls" },
                    brand, battery?.let { "battery ${Math.round(it * 100)}%" },
                    "id %04x:%04x".format(d.vendorId, d.productId),
                ).joinToString(" · "), color = if (external) Hand.Blue else Hand.Muted, fontSize = 13.sp, fontFamily = Hand.Mono)
                if (external) Pads.hint(brand)?.let { Text(it, color = Hand.Muted, fontSize = 13.sp) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { note = if (Pads.rumble(d.id)) "Rumbling ${d.name}…" else "${d.name} has no rumble Android can use." },
                        Modifier.glowFocus(RoundedCornerShape(50))) { Text("Rumble test") }
                    // The safety valve: if the Odin's own controls are ever taken for an external pad, this
                    // stops HandyTuner switching to controller mode because of them.
                    if (external || ignored) Button(onClick = {
                        save(rules.copy(ignoredPads = if (ignored) rules.ignoredPads - d.name else rules.ignoredPads + d.name))
                    }, Modifier.glowFocus(RoundedCornerShape(50))) { Text(if (ignored) "Undo" else "This isn't a controller") }
                }
            }
        }
        note?.let { Text(it, color = Hand.Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Live: every button press with the code it sends, and where the sticks and triggers are. */
@Composable
fun ButtonTestCard(ctx: Context) {
    val act = ctx as? MainActivity ?: return
    val presses = remember { mutableStateListOf<String>() }
    var axes by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var from by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        act.keyHook = { e ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Pads.isPadKey(e.keyCode)) {
                presses.add(0, "${KeyMapper.label(e.keyCode)} (${e.keyCode}) · ${e.device?.name ?: "?"}")
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
    HandCard(Modifier.fillMaxWidth()) {
        CardTitle("Button & stick test", "Press any button, including back paddles, to see what it sends. With the sticks let go, " +
            "they should read close to 0.00; more than 0.10 at rest means drift.")
        if (presses.isEmpty()) Text("Waiting for a button…", color = Hand.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        presses.forEachIndexed { i, p ->
            Text(p, color = if (i == 0) Color.White else Hand.Muted, fontFamily = Hand.Mono, fontSize = 14.sp,
                modifier = Modifier.padding(top = if (i == 0) 8.dp else 0.dp))
        }
        if (axes.isNotEmpty()) {
            Text("Sticks · ${from ?: ""}", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            axes.entries.chunked(2).forEach { row ->
                Text(row.joinToString("    ") { (k, v) -> "$k ${"%+.2f".format(v)}" }, fontFamily = Hand.Mono, fontSize = 14.sp,
                    color = if (row.any { kotlin.math.abs(it.value) > 0.1f && !it.key.startsWith("L2") && !it.key.startsWith("R2") }) Hand.Warn else Hand.Muted)
            }
        }
    }
}

/** Buttons that do a HandyTuner action instead of reaching the game: back paddles are the obvious ones. */
@Composable
fun PadActionsCard(ctx: Context) {
    val act = ctx as? MainActivity ?: return
    var map by remember { mutableStateOf(PadAction.load(ctx)) }
    var listening by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    fun save(m: Map<Int, PadAction>) { map = m; PadAction.save(ctx, m) }
    val hotkeyButtons = remember { Hotkey.HUD.load(ctx) + Hotkey.MENU.load(ctx) }

    DisposableEffect(listening) {
        val before = act.keyHook
        // The first button pressed is the one: recording stops there. Stick movement is ignored, and nothing
        // reaches the screen meanwhile (a stick click would press whatever has focus).
        if (listening) act.keyHook = { e ->
            if (e.action == KeyEvent.ACTION_DOWN && Pads.isRecordable(e.keyCode, e.flags)) { picked = e.keyCode; listening = false }
            true
        }
        onDispose { if (listening) act.keyHook = before }
    }
    LaunchedEffect(listening) { if (listening) { delay(10_000); listening = false } }

    HandCard(Modifier.fillMaxWidth()) {
        CardTitle("Button shortcuts", "Give a button a HandyTuner action, like a screenshot on a back paddle. In games, that button " +
            "then does the action and the game doesn't see it. Works for any controller, the Odin's own too.")
        map.forEach { (code, a) ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${KeyMapper.label(code)} → ${a.label}", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Button(onClick = { save(map - code) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Remove") }
            }
        }
        val p = picked
        if (p == null) {
            Button(onClick = { listening = true }, Modifier.padding(top = 10.dp).glowFocus(RoundedCornerShape(50))) {
                Text(if (listening) "Press the button now…" else "Add a shortcut")
            }
        } else {
            Text("Button: ${KeyMapper.label(p)}. What should it do?", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            if (p in hotkeyButtons) Text("This button is part of a hotkey. Giving it an action keeps it from the game, but the hotkey still works.",
                color = Hand.Warn, fontSize = 13.sp)
            PadAction.entries.forEach { a ->
                Button(onClick = { save(map + (p to a)); picked = null }, Modifier.padding(top = 4.dp).glowFocus(RoundedCornerShape(50))) { Text(a.label) }
            }
            Button(onClick = { picked = null }, Modifier.padding(top = 4.dp).glowFocus(RoundedCornerShape(50))) { Text("Cancel") }
        }
    }
}

/** What changes while an external controller is connected. */
@Composable
fun PadSetupCard(ctx: Context) {
    var r by remember { mutableStateOf(SetupRules.load(ctx)) }
    fun save(n: SetupRules) { r = n; SetupRules.save(ctx, n) }
    HandCard(Modifier.fillMaxWidth()) {
        CardTitle("When a controller is connected", "HandyTuner notices a controller within a few seconds and switches back when it's gone. " +
            "Docked and a controller together is Couch mode, set on the Dock page.")
        PresetPicker("Preset for every game", r.padPreset, "Each game's own", ctx) { save(r.copy(padPreset = it)) }
        SettingSwitch("Ignore the Odin's own buttons", "So a bump on the Odin doesn't press anything. Buttons only (Android doesn't " +
            "let HandyTuner hold back the sticks). The hotkeys still work.", r.ignoreBuiltIn) { save(r.copy(ignoreBuiltIn = it)) }
        SettingSwitch("Hide key-mapping markers", "The dots that show where mapped buttons tap.", r.hideMarkers) { save(r.copy(hideMarkers = it)) }
        SettingSwitch("Controller alerts", "Tell me when a controller disconnects mid-game or its battery is low.", r.padAlerts) { save(r.copy(padAlerts = it)) }
    }
}
