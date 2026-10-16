// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Live ping with a one-minute graph, Wi-Fi and Low Latency, Private DNS; the network test and DNS benchmark drill in. */
@Composable
fun NetworkPage(ctx: Context) {
    val last = remember { Net.lastServer(ctx) }
    val targets = remember {
        listOfNotNull(
            "Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8",
            last?.let { (ip, pkg) -> "Last game (${GameId.label(pkg) ?: pkg.substringAfterLast('.')})" to ip },
        )
    }
    var target by remember { mutableStateOf(0) }   // Cloudflare, so it matches the ping shown on Home
    val samples = remember { mutableStateListOf<Double?>() }     // last 60, null = lost
    val net = remember { Net(ctx) }
    val open = openSub()
    LaunchedEffect(target) {
        samples.clear()
        while (isActive) {
            val ms = withContext(Dispatchers.IO) { net.pingOnce(targets[target].second) }
            samples.add(ms); if (samples.size > 60) samples.removeAt(0)
            delay(1_000)
        }
    }

    ConsolePage {
        RowGroup("Ping") {
            ChoiceRow("Ping to", targets, target, { it.first }, hint = "Once a second, the last minute below", icon = Icons.Rounded.Speed) { target = it }
            Panel { PingStats(samples) }
        }

        RowGroup("Wi-Fi") {
            val wifi = ctx.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") val info = wifi.connectionInfo
            val f = info?.frequency ?: 0
            val band = when (f) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> null }
            InfoRow("Wi-Fi", if (band == null) "Not connected" else "$band · ${info.rssi} dBm · ${info.linkSpeed} Mbps",
                hint = Net.tip(band, info?.rssi?.takeIf { f > 0 }, samples.lastOrNull()?.toInt()))
            var lowLatency by remember { mutableStateOf(Tweaks.lowLatency(ctx)) }
            ToggleRow("Low Latency mode", lowLatency, hint = "Steadier ping on Wi-Fi",
                help = "Keeps the Wi-Fi radio awake between packets and stops background Wi-Fi and Bluetooth scans, which " +
                    "pause the radio. Measured on this Odin: lag spikes to the router 62 → 9 ms. Network only: it never changes " +
                    "PULSE or a game's preset.", icon = Icons.Rounded.NetworkCheck) { lowLatency = it; Tweaks.setLowLatency(ctx, it) }
        }

        RowGroup("DNS") {
            PrivateDnsRow(ctx)
            NavRow("DNS benchmark", hint = "Which DNS answers fastest from here", icon = Icons.Rounded.Dns) { from ->
                open("DNS benchmark", from) { DnsBenchmark(ctx) }
            }
        }

        RowGroup("Tests") {
            NavRow("Network test", hint = "Lag, lag while downloading, lookups, and what to fix", icon = Icons.Rounded.Wifi) { from ->
                open("Network test", from) { NetTestScreen(ctx) }
            }
        }
    }
}

/** Now / average / jitter / lost, and the minute's graph with the 50 ms line. */
@Composable
private fun PingStats(samples: List<Double?>) {
    val ok = samples.filterNotNull()
    val now = samples.lastOrNull()
    val jitter = ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.takeIf { it.isNotEmpty() }?.average()
    val loss = if (samples.isEmpty()) 0 else samples.count { it == null } * 100 / samples.size
    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        @Composable fun stat(label: String, v: String, c: Color) = Column {
            Text(label, color = Hand.Muted, fontSize = 13.sp); Text(v, color = c, fontFamily = Hand.Mono, fontSize = 24.sp)
        }
        stat("Now", now?.let { "%.0f ms".format(it) } ?: "–", if ((now ?: 999.0) < 50) Hand.Good else if ((now ?: 999.0) < 100) Hand.Warn else Hand.Bad)
        stat("Average", ok.takeIf { it.isNotEmpty() }?.average()?.let { "%.0f ms".format(it) } ?: "–", Hand.Text)
        stat("Jitter", jitter?.let { "±%.0f ms".format(it) } ?: "–", if ((jitter ?: 0.0) < 15) Hand.Good else Hand.Warn)
        stat("Lost", "$loss%", if (loss == 0) Hand.Good else Hand.Bad)
    }
    val bad = Hand.Bad; val line = if (Hand.light) Hand.Track else Hand.Bar; val blue = Hand.Blue
    // The last minute, one point a second; red ticks where a ping got no answer.
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val max = (ok.maxOrNull() ?: 100.0).coerceAtLeast(60.0) * 1.2
        val dx = size.width / 59f
        val path = Path()
        var started = false
        samples.forEachIndexed { i, v ->
            val x = i * dx
            if (v == null) { drawLine(bad, Offset(x, 0f), Offset(x, size.height), 3f); started = false; return@forEachIndexed }
            val y = size.height - (v / max * size.height).toFloat()
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        val y50 = size.height - (50 / max * size.height).toFloat()
        drawLine(line, Offset(0f, y50), Offset(size.width, y50), 2f)        // the 50 ms "good" line
        drawPath(path, blue, style = Stroke(width = 5f))
    }
    Text("The gray line is 50 ms: under it feels instant in most online games.", color = Hand.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}

/** Private DNS: Automatic, or one of the well-known providers that can be set as Private DNS. */
@Composable
private fun PrivateDnsRow(ctx: Context) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(privateDns(ctx)) }
    val options = remember { listOf("Automatic" to null) + Net.DNS_PROVIDERS.filter { it.third != null }.map { it.first to it.third } }
    ChoiceRow("Private DNS", options, options.indexOfFirst { mode.startsWith(it.first) }, { it.first },
        hint = if (options.none { mode.startsWith(it.first) }) "Now: $mode" else "Encrypted lookups, set for the whole Odin",
        help = "Automatic uses your network's own DNS. A provider here turns names into addresses for every app, encrypted; " +
            "the DNS benchmark shows which is fastest from here. Reset everything to stock puts it back.",
        icon = Icons.Rounded.Public) { i -> scope.launch { setPrivateDns(ctx, options[i].second); mode = privateDns(ctx) } }
}

private suspend fun setPrivateDns(ctx: Context, host: String?) = withContext(Dispatchers.IO) {
    if (host == null) Originals.put(ctx, "global:private_dns_mode", "opportunistic")
    else { Originals.put(ctx, "global:private_dns_specifier", host); Originals.put(ctx, "global:private_dns_mode", "hostname") }
}

/**
 * Your network's own DNS and the well-known public providers, 10 lookups each, one at a time so they don't slow
 * each other. Only these: no third-party list of strangers' servers is fetched or probed.
 */
@Composable
private fun DnsBenchmark(ctx: Context) {
    val scope = rememberCoroutineScope()
    val system = remember {
        ctx.getSystemService(ConnectivityManager::class.java).let { cm -> cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull() }
    }
    var results by remember { mutableStateOf<List<Net.DnsResult>?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(privateDns(ctx)) }
    fun bench() = scope.launch(Dispatchers.IO) {
        val known = Net.DNS_PROVIDERS.map { Triple(it.first, it.second, it.third) }
        val list = listOfNotNull(system?.let { Triple("Your network", it.hostAddress ?: "", null as String?) }) + known
        val out = list.mapIndexed { i, (name, ip, dot) ->
            withContext(Dispatchers.Main) { testing = "Testing ${i + 1} of ${list.size} ($name)…" }
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
            val ms = List(10) { addr?.let { a -> Net.dnsMs(a).also { Thread.sleep(40) } } }
            Net.DnsResult(name, ip, dot, ms.filterNotNull(), ms.count { it == null })
        }
        withContext(Dispatchers.Main) { results = Net.rank(out); testing = null }
    }

    ConsolePage {
        RowGroup {
            ActionRow(if (results == null) "Run the benchmark" else "Run it again", enabled = testing == null,
                hint = testing ?: "About 20 seconds", icon = Icons.Rounded.Dns,
                help = "DNS turns names into addresses. A faster one makes games, stores and matchmaking connect sooner; it doesn't change in-game ping.") {
                results = null; bench()
            }
        }
        results?.let { all ->
            // The top 10, plus your own DNS wherever it placed, so you can see how it compares.
            val shown = all.take(10) + all.drop(10).filter { it.name == "Your network" }
            RowGroup("Fastest first") {
                shown.forEachIndexed { i, x ->
                    InfoRow("${all.indexOf(x) + 1}. ${x.name}", (x.median?.let { "$it ms" } ?: "no answer") + (if (x.lost > 0) " · ${x.lost}/10 lost" else ""),
                        hint = x.avg?.let { "average $it ms" }, valueColor = if (i == 0) Hand.Good else null)
                }
            }
            val best = all.firstOrNull { it.dot != null && it.median != null }
            RowGroup {
                if (best != null) ActionRow(if (mode == best.name) "${best.name} is your Private DNS" else "Use ${best.name} as Private DNS",
                    hint = "The fastest that can be set as Private DNS", enabled = mode != best.name) {
                    scope.launch { setPrivateDns(ctx, best.dot); mode = privateDns(ctx) }
                }
            }
            if (all.first().dot == null && all.first().name != "Your network") Note(
                "The fastest, ${all.first().name} (${all.first().ip}), can't be set as Private DNS; you can set it in your router instead.")
        }
    }
}

private fun privateDns(ctx: Context): String {
    val m = Settings.Global.getString(ctx.contentResolver, "private_dns_mode")
    val h = Settings.Global.getString(ctx.contentResolver, "private_dns_specifier")
    return when {
        m == "hostname" && h == "one.one.one.one" -> "Cloudflare"
        m == "hostname" && h == "dns.google" -> "Google"
        m == "hostname" && h == "dns.quad9.net" -> "Quad9"
        m == "hostname" && h == "dns.adguard-dns.com" -> "AdGuard"
        m == "hostname" -> "Custom ($h)"
        m == "off" -> "Off"
        else -> "Automatic"
    }
}
