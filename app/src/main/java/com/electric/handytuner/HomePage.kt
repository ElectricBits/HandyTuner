// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * At a glance: a greeting, battery / network / PULSE tiles (A opens that section), the last game with its
 * preset, and the hotkeys as recorded. Everything here is read live; only the last game's preset can be set.
 */
@Composable
fun HomePage(ctx: Context, actions: Actions, hudKeys: String, menuKeys: String) {
    data class Snap(
        val pct: Int, val charging: Boolean, val hoursLeft: Double?, val watts: Double?,
        val plugged: Boolean, val ping: Int?, val band: String?, val rssi: Int?, val linkMbps: Int?, val pulseRunning: Boolean,
    )
    var snap by remember { mutableStateOf<Snap?>(null) }
    val net = remember { Net(ctx) }
    val goTo = LocalGoTo.current
    LaunchedEffect(Unit) {
        while (isActive) {
            snap = withContext(Dispatchers.IO) {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                // BatteryManager.isCharging() said false on the Odin at 5 A in; the sticky status is right.
                val sticky = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val drainMa = kotlin.math.abs(ua) / 1000.0
                val volts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.div(1000.0)
                val wifi = ctx.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION") val info = wifi.connectionInfo
                val f = info?.frequency ?: 0
                Snap(
                    pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), charging = charging,
                    plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                    hoursLeft = if (!charging && drainMa > 50 && uah > 0) uah / 1000.0 / drainMa else null,
                    watts = if (volts != null && volts > 0) drainMa * volts / 1000.0 else null,   // in or out, per [charging]
                    ping = net.netTest().ms,
                    band = when (f) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> null },
                    rssi = info?.rssi?.takeIf { f > 0 }, linkMbps = info?.linkSpeed?.takeIf { f > 0 },
                    pulseRunning = actions.pulseInstalled() && actions.pulseRunning(),
                )
            }
            delay(10_000)
        }
    }

    ConsolePage {
        Greeting()
        if (About.untestedDevice) Note("Not tested on this ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}: some features may not " +
            "work. If anything goes wrong, System › Reset everything to stock puts it back.", Hand.Warn)

        val s = snap
        // Same height for all three tiles, whatever each one has to say.
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            if (s == null) Note("Checking…")
            else {
                StatusTile(Icons.Rounded.BatteryChargingFull, "Battery", "${s.pct}%",
                    if (s.pct > 30) Hand.Good else if (s.pct > 15) Hand.Warn else Hand.Bad,
                    listOfNotNull(when {
                        s.charging && (s.watts ?: 0.0) < 0.5 -> "Plugged in, full"
                        s.charging -> s.watts?.let { "Charging at %.1f W".format(it) } ?: "Charging"
                        // On the charger but held at the charge limit (or gaming straight from the charger).
                        s.plugged -> "Plugged in, not charging"
                        s.hoursLeft != null -> "About %dh %02dm left".format(s.hoursLeft.toInt(), ((s.hoursLeft % 1) * 60).toInt())
                        else -> "Measuring…"
                    }, if (!s.charging && !s.plugged) s.watts?.let { "Using %.1f W".format(it) } else null), tile) { goTo(Section.BATTERY) }
                StatusTile(Icons.Rounded.Wifi, "Network", s.ping?.let { "$it ms" } ?: "no reply",
                    when { s.ping == null -> Hand.Bad; s.ping < 50 -> Hand.Good; s.ping < 100 -> Hand.Warn; else -> Hand.Bad },
                    listOf(if (s.band != null) "${s.band} · ${s.rssi} dBm" else "Not on Wi-Fi", Net.tip(s.band, s.rssi, s.ping)), tile) { goTo(Section.NETWORK) }
                StatusTile(Icons.Rounded.Memory, "PULSE engine", if (s.pulseRunning) "Running" else "Stopped",
                    if (s.pulseRunning) Hand.Good else Hand.Muted, listOf("Fan and clocks"), tile) { goTo(Section.PERFORMANCE) }
            }
        }

        LastGame(ctx)

        RowGroup("Hotkeys") {
            NavRow("HUD", hint = "Show or hide the stats box in any game", icon = Icons.Rounded.Insights, value = hudKeys) { goTo(Section.HUD) }
            NavRow("Quick Menu", hint = "Open the menu over any game", icon = Icons.Rounded.Tune, value = menuKeys) { goTo(Section.HUD) }
        }
    }
}

@Composable
private fun Greeting() = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning!"; in 12..17 -> "Good afternoon!"; in 18..23 -> "Good evening!"; else -> "Hey, night owl!" }
    // Tap him and he moonwalks. Touch only: as a clickable he took D-pad focus with nothing to show for it.
    var dance by remember { mutableStateOf(0) }
    Box(Modifier.pointerInput(Unit) { detectTapGestures { dance++ } }) {
        HelperAvatar(talking = false, mood = Mood.IDLE, hops = 0, width = 132.dp, dance = dance)
    }
    Column(Modifier.padding(start = 18.dp)) {
        Text(greeting, color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
        Text("Here's your Odin right now.", color = Hand.Muted, fontSize = 16.sp)
    }
}

/** The game played last and its preset, changeable right here (the same as on Games). */
@Composable
private fun LastGame(ctx: Context) {
    val last = remember { Sessions.all(ctx).firstOrNull() } ?: return
    val presets = remember { Preset.entries.map { it.def } + CustomPresets.all(ctx) }
    var s by remember { mutableStateOf(GameStore.get(ctx, last.pkg)) }
    val name = GameId.label(last.pkg) ?: last.label
    RowGroup("Last played") {
        val current = s
        ChoiceRow(name, presets, presets.indexOfFirst { it.key == (current?.preset?.key ?: Preset.NEW_GAME.def.key) },
            { p -> if (current != null && current.tweaked && p.key == current.preset.key) "${p.label} (tweaked)" else p.label },
            hint = current?.describe() ?: "New game: starts on ${Preset.NEW_GAME.label}",
            help = "Its preset is applied every time it opens. Changes from the Quick Menu are saved to it.",
            icon = Icons.Rounded.SportsEsports) { i ->
            GameStore.set(ctx, last.pkg, GameSettings.of(presets[i])); s = GameStore.get(ctx, last.pkg)
        }
    }
}
