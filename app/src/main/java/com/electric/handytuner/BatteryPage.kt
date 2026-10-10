// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import kotlin.math.roundToInt
import java.util.Date
import java.util.Locale

/** Play services and play-session reports. (Per-game presets moved to the Games page.) */
@Composable
fun BatteryPage(ctx: Context) {
    val sessions = remember { Sessions.all(ctx) }
    @Composable fun heading(t: String, sub: String? = null) = Column(Modifier.padding(top = 8.dp)) {
        Text(t, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
        sub?.let { Text(it, color = Hand.Muted, fontSize = 14.sp) }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ChargeCard(ctx) }
        item { PlayServicesCard(ctx) }
        item { heading("Play sessions", if (sessions.isEmpty()) "Nothing yet: play a game for a minute or more." else "Most recent first.") }
        items(sessions) { s ->
            HandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(s.start)), color = Hand.Muted, fontSize = 13.sp)
                    }
                    Text("${s.minutes} min", color = Color.White, fontFamily = Hand.Mono, modifier = Modifier.padding(end = 20.dp))
                    Text(if (s.charging) "charging" else "−${s.used}%", color = if (s.charging) Hand.Muted else Hand.Warn,
                        fontFamily = Hand.Mono, modifier = Modifier.padding(end = 20.dp))
                    Text(s.drainPerHour?.let { "%.0f%%/h".format(it) } ?: "", color = Hand.Muted, fontFamily = Hand.Mono)
                }
            }
        }
    }
}

/** Charge limit + play-while-charging (ChargeRule). Saving the first time turns it on; Reset turns it off. */
@Composable
private fun ChargeCard(ctx: Context) {
    var s by remember { mutableStateOf(ChargeRule.load(ctx)) }
    var on by remember { mutableStateOf(ChargeRule.file(ctx).exists()) }
    fun set(n: ChargeRule.Settings) { s = n; ChargeRule.save(ctx, n); on = true }
    HandCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Charging", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        Text("Stops charging at the limit and starts again 5% below. While a game runs on the charger, the battery " +
            "is skipped so it stays cool." + if (on) "" else " Off until you change a setting here.", color = Hand.Muted, fontSize = 14.sp)
        Text(if (s.limit >= 100) "No limit" else "Stop at ${s.limit}%", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        Slider(value = s.limit.toFloat(), onValueChange = { set(s.copy(limit = (it / 5).roundToInt() * 5)) }, valueRange = 50f..100f, steps = 9)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Skip the battery while gaming", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            // Off until the feature is on: the switch reads the saved value, which is on by default.
            Switch(checked = on && s.gamingBypass, onCheckedChange = { set(s.copy(gamingBypass = it)) })
        }
    }
}

/** Odin Assistant's Google Play services switch, with what it costs spelled out. Reset turns it back on. */
@Composable
private fun PlayServicesCard(ctx: Context) {
    val actions = remember { Actions(ctx) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var on by remember { mutableStateOf(actions.playServicesOn()) }
    var confirm by remember { mutableStateOf(false) }
    HandCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Google Play services", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        Text(if (on) "On. Turning it off frees memory and background work while you play, but notifications, " +
            "Google sign-ins and the Play Store stop until you turn it back on." else "Off. Turn it back on for notifications, sign-ins and the Play Store.",
            color = Hand.Muted, fontSize = 14.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fun flip(v: Boolean) = scope.launch(Dispatchers.IO) {
                actions.setPlayServices(v); val now = actions.playServicesOn()
                withContext(Dispatchers.Main) { on = now; confirm = false }
            }
            when {
                !on -> Button(onClick = { flip(true) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Turn back on") }
                !confirm -> Button(onClick = { confirm = true }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Turn off…") }
                else -> {
                    Button(onClick = { flip(false) }, Modifier.glowFocus(RoundedCornerShape(50)),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Hand.Bad)) { Text("Yes, turn off") }
                    Button(onClick = { confirm = false }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Cancel") }
                }
            }
        }
    }
}
