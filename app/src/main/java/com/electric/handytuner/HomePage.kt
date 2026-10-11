// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar

/** At a glance: battery, network, Pulse. Everything here is read live; nothing is set. */
@Composable
fun HomePage(ctx: Context, actions: Actions) {
    data class Snap(
        val pct: Int, val charging: Boolean, val hoursLeft: Double?, val watts: Double?,
        val ping: Int?, val band: String?, val rssi: Int?, val linkMbps: Int?,
        val pulseInstalled: Boolean, val pulseRunning: Boolean,
    )
    var snap by remember { mutableStateOf<Snap?>(null) }
    val net = remember { Net(ctx) }
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
                    hoursLeft = if (!charging && drainMa > 50 && uah > 0) uah / 1000.0 / drainMa else null,
                    watts = if (volts != null && volts > 0) drainMa * volts / 1000.0 else null,   // in or out, per [charging]
                    ping = net.netTest().ms,
                    band = when (f) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> null },
                    rssi = info?.rssi?.takeIf { f > 0 }, linkMbps = info?.linkSpeed?.takeIf { f > 0 },
                    pulseInstalled = actions.pulseInstalled(), pulseRunning = actions.pulseRunning(),
                )
            }
            delay(10_000)
        }
    }

    // Scrolls: the page is taller than a 1080p screen, and the last card was cut off.
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning!"; in 12..17 -> "Good afternoon!"; in 18..23 -> "Good evening!"; else -> "Hey, night owl!" }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        // Tap him and he moonwalks.
        var dance by remember { mutableStateOf(0) }
        Box(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { dance++ }) {
            HelperAvatar(talking = false, mood = Mood.IDLE, hops = 0, width = 160.dp, dance = dance)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(greeting, color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            Text("Here's your Odin right now.", color = Hand.Muted, fontSize = 15.sp)
        }
    }
    val s = snap ?: run { Text("Checking…", color = Hand.Muted); return@Column }

    @Composable fun card(icon: Int, title: String, modifier: Modifier, body: @Composable () -> Unit) = HandCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, tint = Hand.Blue, modifier = Modifier.size(28.dp))
            Text("  $title", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Column(Modifier.padding(top = 8.dp)) { body() }
    }
    @Composable fun big(t: String, c: Color = Hand.Text) = Text(t, color = c, fontFamily = Hand.Mono, fontSize = 26.sp)
    @Composable fun line(t: String, c: Color = Hand.Muted) = Text(t, color = c, fontSize = 14.sp)

    UntestedDeviceCard()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        card(R.drawable.ic_battery_horiz_075, "Battery", Modifier.weight(1f)) {
            big("${s.pct}%", if (s.pct > 30) Hand.Good else if (s.pct > 15) Hand.Warn else Hand.Bad)
            line(when {
                s.charging && (s.watts ?: 0.0) < 0.5 -> "Plugged in, full"
                s.charging -> s.watts?.let { "Charging at %.1f W".format(it) } ?: "Charging"
                s.hoursLeft != null -> "About %dh %02dm left at this rate".format(s.hoursLeft.toInt(), ((s.hoursLeft % 1) * 60).toInt())
                else -> "Measuring…"
            })
            if (!s.charging) s.watts?.let { line("Using %.1f W".format(it)) }
        }
        card(R.drawable.ic_wifi, "Network", Modifier.weight(1f)) {
            big(s.ping?.let { "${it} ms" } ?: "no reply",
                when { s.ping == null -> Hand.Bad; s.ping < 50 -> Hand.Good; s.ping < 100 -> Hand.Warn; else -> Hand.Bad })
            line(if (s.band != null) "${s.band} · ${s.rssi} dBm · ${s.linkMbps} Mbps" else "Not on Wi-Fi")
            val tip = Net.tip(s.band, s.rssi, s.ping)
            line(tip, Hand.Text)
        }
        card(R.drawable.ic_mode_fan, "PULSE engine", Modifier.weight(1f)) {
            big(if (s.pulseRunning) "Running" else "Stopped",
                if (s.pulseRunning) Hand.Good else Hand.Muted)
            line("Controls performance, fan and clocks. Its settings are on the Tweaks page.")
        }
    }
    HandCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("In any game", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        line("HUD: both back buttons + both sticks.   Quick Menu: both sticks + R1.   Change them on the HUD page.")
    }
    AboutCard(ctx)
    }
}
