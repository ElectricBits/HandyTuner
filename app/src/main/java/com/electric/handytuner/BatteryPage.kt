// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
