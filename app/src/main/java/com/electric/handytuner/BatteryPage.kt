// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Charging, Google Play services, and play-session reports. (Per-game presets are on Games.) */
@Composable
fun BatteryPage(ctx: Context) {
    val sessions = remember { Sessions.all(ctx) }
    val open = openSub()
    ConsolePage {
        ChargeGroup(ctx)
        PlayServicesGroup(ctx)
        RowGroup("History") {
            NavRow("Play sessions", hint = if (sessions.isEmpty()) "Nothing yet: play a game for a minute or more" else "Battery used per game, newest first",
                icon = Icons.Rounded.History, value = sessions.size.takeIf { it > 0 }?.toString(), enabled = sessions.isNotEmpty()) { from ->
                open("Play sessions", from) { SessionsList(sessions) }
            }
        }
    }
}

/** Charge limit + play-while-charging (ChargeRule). Saving the first time turns it on; Reset turns it off. */
@Composable
private fun ChargeGroup(ctx: Context) {
    var s by remember { mutableStateOf(ChargeRule.load(ctx)) }
    var on by remember { mutableStateOf(ChargeRule.file(ctx).exists()) }
    fun set(n: ChargeRule.Settings) { s = n; ChargeRule.save(ctx, n); on = true }
    RowGroup("Charging") {
        SliderRow("Charge limit", s.limit, 50..100, 5, { if (it >= 100) "No limit" else "$it%" },
            hint = if (on) "Stops here, starts again 5% below" else "Off until you change a setting here",
            help = "Keeping the battery below full makes it last longer. 100 means no limit.",
            icon = Icons.Rounded.BatteryChargingFull) { set(s.copy(limit = it)) }
        // Off until the feature is on: the switch reads the saved value, which is on by default.
        ToggleRow("Skip the battery while gaming", on && s.gamingBypass, hint = "Plays straight from the charger",
            help = "While a game runs on the charger, power goes to the Odin instead of the battery, so the battery stays cool.",
            icon = Icons.Rounded.SportsEsports) { set(s.copy(gamingBypass = it)) }
    }
}

/** Odin Assistant's Google Play services switch, with what it costs spelled out. Reset turns it back on. */
@Composable
private fun PlayServicesGroup(ctx: Context) {
    val actions = remember { Actions(ctx) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(actions.playServicesOn()) }
    var busy by remember { mutableStateOf(false) }
    RowGroup("Background") {
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
}

@Composable
private fun SessionsList(sessions: List<Sessions.Session>) = ConsolePage {
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    RowGroup {
        sessions.forEach { s ->
            InfoRow(s.label, listOfNotNull("${s.minutes} min", if (s.charging) "charging" else "−${s.used}%",
                s.drainPerHour?.let { "%.0f%%/h".format(it) }).joinToString("  ·  "), hint = fmt.format(Date(s.start)))
        }
    }
}
