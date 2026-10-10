// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Properties

/**
 * Tweaks the owner can switch on for everything, not just one game (owner's call, 2026-09-30). A file,
 * like the other settings, because the :overlay process applies them.
 */
object Tweaks {
    fun file(ctx: Context) = File(ctx.filesDir, "tweaks.properties")
    private fun load(ctx: Context) = runCatching { Properties().apply { file(ctx).inputStream().use { load(it) } } }.getOrDefault(Properties())
    private fun flag(ctx: Context, key: String, default: Boolean) = load(ctx).getProperty(key)?.toBoolean() ?: default
    private fun set(ctx: Context, key: String, on: Boolean) = file(ctx).storeAtomic(load(ctx).apply { setProperty(key, on.toString()) })

    fun lowLatency(ctx: Context) = flag(ctx, "lowLatency", false)
    fun setLowLatency(ctx: Context, on: Boolean) = set(ctx, "lowLatency", on)

    /** Keep Odin Assistant's game detection off (it fights the PULSE engine). On by default; the owner's choice. */
    fun assistantOff(ctx: Context) = flag(ctx, "assistantOff", true)
    fun setAssistantOff(ctx: Context, on: Boolean) = set(ctx, "assistantOff", on)
}

@Composable
fun TweaksPage(ctx: Context) {
    var lowLatency by remember { mutableStateOf(Tweaks.lowLatency(ctx)) }
    var tab by remember { mutableStateOf(0) }
    // Sub-tabs: one-off fixes vs the PULSE engine knobs.
    Column(Modifier.fillMaxSize()) {
        SubTabs(listOf("Quick fixes", "Engine"), tab) { tab = it }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                HandCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Low Latency mode", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("Network only: it never changes PULSE or a game's preset.", color = Hand.Muted, fontSize = 14.sp)
                        }
                        Switch(checked = lowLatency, onCheckedChange = { lowLatency = it; Tweaks.setLowLatency(ctx, it) },
                            modifier = Modifier.glowFocus(RoundedCornerShape(50)))
                    }
                    Text("What it does", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    listOf(
                        "Wi-Fi low-latency mode" to "Stops the Wi-Fi radio napping between packets.",
                        "Background Wi-Fi and Bluetooth scans off" to "Scans pause the radio and cause spikes.",
                    ).forEach { (what, why) -> Text("• $what: $why", color = Hand.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Text("Measured on this Odin", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Text("Lag spikes to the router: 62 → 9 ms (worst 1%). Internet: worst 5% 81 → 44 ms, jitter 9.5 → 2.5 ms. " +
                        "800 pings each way, 2026-09-30.", color = Hand.Muted, fontSize = 14.sp)
                    Text("Try it on your own Wi-Fi: Network → Network test → Test Low Latency.", color = Hand.Blue, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                SpeedUpCard(ctx)
                HandCard(Modifier.fillMaxWidth()) {
                    val actions = remember { Actions(ctx) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var removed by remember { mutableStateOf(!actions.odinMenuOn()) }
                    var busy by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remove Odin's swipe-in menu", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("Swiping in from the right edge opens Odin's own game panel. On disables the GameAssistant " +
                                "app behind it — but Odin keeps that app running, so the panel only goes away after you " +
                                "restart the Odin. Off turns it back on, again after a restart. Reset everything to stock " +
                                "also turns it back on.", color = Hand.Muted, fontSize = 14.sp)
                        }
                        Switch(checked = removed, enabled = !busy, onCheckedChange = { want ->
                            busy = true
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                actions.setOdinMenu(!want)
                                val now = !actions.odinMenuOn()
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { removed = now; busy = false }
                            }
                        }, modifier = Modifier.glowFocus(RoundedCornerShape(50)))
                    }
                }
                HandCard(Modifier.fillMaxWidth()) {
                    var off by remember { mutableStateOf(Tweaks.assistantOff(ctx)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep Odin Assistant's game detection off", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("Odin Assistant can change performance and fan per game, which fights HandyTuner. This switches off " +
                                "only its game detection (an accessibility service); the app and its gamepad test stay. Turning this " +
                                "off leaves it alone; switch it back on in Settings › Accessibility if you want it.", color = Hand.Muted, fontSize = 14.sp)
                        }
                        Switch(checked = off, onCheckedChange = { off = it; Tweaks.setAssistantOff(ctx, it) },
                            modifier = Modifier.glowFocus(RoundedCornerShape(50)))
                    }
                }
            } else {
                TuningCards(ctx)
            }
        }
    }
}

/** Speed Up owns closing background apps (owner's call): never your last game, Pulse or HandyTuner. */
@Composable
private fun SpeedUpCard(ctx: Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    HandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speed Up", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("Closes background apps and clears their cards from Recents. Never your last game, PULSE or HandyTuner, " +
                    "and never an app that's downloading.", color = Hand.Muted, fontSize = 14.sp)
            }
            androidx.compose.material3.Button(enabled = !busy, onClick = {
                busy = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val mb = Actions(ctx).speedUp()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { note = "Freed $mb MB"; busy = false }
                }
            }, modifier = Modifier.glowFocus(RoundedCornerShape(50))) { Text("Speed Up") }
        }
        note?.let { Text(it, color = Hand.Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}
