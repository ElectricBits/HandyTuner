// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The PULSE engine's device-wide settings, as one page sees them. The settings belong to the :pulse process, so
 * every read and write goes through the link; [settings] is null until the engine answers, [note] says why not.
 */
class Engine(private val link: PulseLink, private val scope: CoroutineScope, val safe: Boolean) {
    var settings by mutableStateOf<JSONObject?>(null)
    var note by mutableStateOf<String?>(null)

    fun set(key: String, value: String) = scope.launch {
        val ok = withContext(Dispatchers.IO) { link.call { it.setEngineSetting(key, value) } == true }
        if (ok) settings = withContext(Dispatchers.IO) { link.engineSettings() } ?: settings
        note = if (ok) null else when (key) {
            "sleep" -> "Couldn't switch sleep underclock on: the CPU limits couldn't be read."
            else -> "That change didn't go through."
        }
    }
}

/** A connected [Engine] for this page: links on entry, unlinks on exit, and loads the settings once it answers. */
@Composable
fun rememberEngine(ctx: Context): Engine {
    val link = remember { PulseLink(ctx, publishes = false) }
    DisposableEffect(Unit) { link.connect(); onDispose { link.disconnect() } }
    val scope = rememberCoroutineScope()
    val engine = remember { Engine(link, scope, SafeMode.active(ctx)) }
    LaunchedEffect(Unit) {
        repeat(20) {   // the link comes up asynchronously; give it a few seconds
            engine.settings = withContext(Dispatchers.IO) { if (link.supports(Applied.Feature.ENGINE_SETTINGS)) link.engineSettings() else null }
            if (engine.settings != null) return@LaunchedEffect
            delay(250)
        }
        engine.note = "The PULSE engine isn't answering. System › Permissions & apps shows why."
    }
    return engine
}

/** How the PULSE engine runs everywhere (presets pick *which* mode a game gets), and what runs in the background. */
@Composable
fun PerformancePage(ctx: Context) {
    val engine = rememberEngine(ctx)
    ConsolePage {
        if (engine.safe) Note("Safe mode is on, so nothing here can change. Clear it in System.", Hand.Warn)
        engine.note?.let { Note(it, Hand.Warn) }
        val st = engine.settings
        if (st == null && engine.note == null) Note("Connecting to the PULSE engine…")
        val on = !engine.safe

        if (st != null) {
            RowGroup("Fan") {
                SliderRow("Hold temp target", st.optInt("fanTargetC", 78), 60..88, 1, { "$it °C" },
                    hint = "For presets that use the Hold temp fan",
                    help = "The temperature the Hold temp fan keeps the chip at. Lower is cooler and louder. Applies to every preset that uses Hold temp.",
                    icon = Icons.Rounded.Thermostat, enabled = on) { engine.set("fanTargetC", it.toString()) }
            }

            RowGroup("AutoTDP") {
                val styles = listOf("EFFICIENT" to "Efficient", "BALANCED" to "Balanced", "SMOOTH" to "Smooth")
                ChoiceRow("AutoTDP style", styles, styles.indexOfFirst { it.first == st.optString("autoTdpBias") }, { it.second },
                    hint = "How hard it saves power while holding your FPS",
                    help = "Efficient saves the most battery, Smooth keeps the most headroom. Applies to every preset that uses AutoTDP.",
                    icon = Icons.Rounded.Speed, enabled = on) { engine.set("autoTdpBias", styles[it].first) }
            }

            RowGroup("Sleep") {
                ToggleRow("Sleep underclock", st.optBoolean("sleep"), hint = "Slows the chip while the screen is off",
                    help = "Saves battery in sleep, and puts the speed back the moment you wake the Odin.",
                    icon = Icons.Rounded.Bedtime, enabled = on) { engine.set("sleep", if (it) "on" else "off") }
            }
        }

        BackgroundGroup(ctx)
    }
}

/** What runs behind your game: background apps, Google Play services, and Odin Assistant's game detection. */
@Composable
private fun BackgroundGroup(ctx: Context) {
    val scope = rememberCoroutineScope()
    val actions = remember { Actions(ctx) }
    RowGroup("Background") {
        var freed by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        ActionRow("Speed Up", hint = "Closes background apps", value = if (busy) "Working…" else freed, enabled = !busy,
            help = "Clears their cards from Recents too. Never your last game, PULSE or HandyTuner, and never an app that's downloading.",
            icon = Icons.Rounded.RocketLaunch) {
            busy = true
            scope.launch(Dispatchers.IO) {
                val mb = actions.speedUp()
                withContext(Dispatchers.Main) { freed = "Freed $mb MB"; busy = false }
            }
        }

        PlayServicesRow(ctx)

        var assistantOff by remember { mutableStateOf(Tweaks.assistantOff(ctx)) }
        ToggleRow("Keep Odin Assistant's game detection off", assistantOff, hint = "It fights HandyTuner's presets",
            help = "Odin Assistant can change performance and fan per game. This switches off only its game detection (an " +
                "accessibility service); the app and its gamepad test stay. Off leaves it alone; switch it back on in " +
                "Settings › Accessibility if you want it.", icon = Icons.Rounded.VisibilityOff) { assistantOff = it; Tweaks.setAssistantOff(ctx, it) }
    }
}

/** Odin Assistant's Google Play services switch, with what it costs spelled out. Reset turns it back on. */
@Composable
internal fun PlayServicesRow(ctx: Context) {
    val actions = remember { Actions(ctx) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(actions.playServicesOn()) }
    var busy by remember { mutableStateOf(false) }
    ToggleRow("Google Play services", on, enabled = !busy,
        hint = if (on) "On: notifications, sign-ins and the Play Store work" else "Off: frees memory while you play",
        help = if (on) null else "Turn it back on for notifications, Google sign-ins and the Play Store.",
        confirmOff = "Off frees memory and background work while you play, but notifications, Google sign-ins and the Play Store stop until you turn it back on.",
        icon = Icons.Rounded.Cloud) { want ->
        busy = true
        scope.launch(Dispatchers.IO) {
            actions.setPlayServices(want); val now = actions.playServicesOn()
            withContext(Dispatchers.Main) { on = now; busy = false }
        }
    }
}

/** The lights around the sticks, through the engine (Controller section). */
@Composable
fun StickLightsGroup(ctx: Context) {
    val engine = rememberEngine(ctx)
    val st = engine.settings ?: return
    val on = !engine.safe
    RowGroup("Stick lights") {
        val modes = listOf("OFF" to "Off", "BATTERY" to "Battery", "HEAT" to "Heat", "MANUAL" to "Color")
        val mode = st.optString("rgbMode")
        ChoiceRow("Lights", modes, modes.indexOfFirst { it.first == mode }, { it.second },
            hint = "The rings around the sticks",
            help = "Battery and Heat change color as the battery drains or the chip warms. Color is one color you pick.",
            icon = Icons.Rounded.Lightbulb, enabled = on) { engine.set("rgbMode", modes[it].first) }
        if (mode == "MANUAL") {
            val color = st.optString("rgbColor", "3F6BFF")
            val bright = st.optInt("rgbBrightness", 100)
            ChoiceRow("Color", SWATCHES, SWATCHES.indexOfFirst { it.first.equals(color, ignoreCase = true) }, { it.second },
                icon = Icons.Rounded.Palette, enabled = on, dot = { Color(0xFF000000 or it.first.toLong(16)) }) {
                engine.set("rgbColor", "${SWATCHES[it].first},$bright")
            }
            SliderRow("Brightness", bright, 0..100, 5, { "$it%" }, icon = Icons.Rounded.Brightness6, enabled = on) {
                engine.set("rgbColor", "$color,$it")
            }
        }
    }
    engine.note?.let { Note(it, Hand.Warn) }
}

/** The stick-light colors on offer: what the hardware shows well. */
private val SWATCHES = listOf(
    "FF0000" to "Red", "FF8000" to "Orange", "FFFF00" to "Yellow", "00FF40" to "Green",
    "00E5FF" to "Cyan", "3F6BFF" to "Blue", "B000FF" to "Purple", "FFFFFF" to "White",
)
