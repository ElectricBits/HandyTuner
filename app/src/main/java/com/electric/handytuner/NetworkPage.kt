// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Ping graph, Wi-Fi quality, and DNS. Low Ping itself lives in the Quick Menu and
 * the game profiles; this page explains it rather than adding a third switch.
 */
@Composable
fun NetworkPage(ctx: Context) {
    val last = remember { Net.lastServer(ctx) }
    val targets = remember {
        listOfNotNull(
            "Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8",
            last?.let { (ip, pkg) -> "Last game (${GameId.label(pkg) ?: pkg.substringAfterLast('.')})" to ip },
        )
    }
    var target by remember { mutableStateOf(targets.first().second) }   // Cloudflare, so it matches the ping shown on Home
    val samples = remember { mutableStateListOf<Double?>() }     // last 60, null = lost
    val net = remember { Net(ctx) }
    var testing by remember { mutableStateOf(false) }            // the Network test runs: its numbers need a quiet line
    LaunchedEffect(target, testing) {
        samples.clear()
        while (isActive && !testing) {
            val ms = withContext(Dispatchers.IO) { net.pingOnce(target) }
            samples.add(ms); if (samples.size > 60) samples.removeAt(0)
            delay(1_000)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HandCard(Modifier.fillMaxWidth()) {
            Row {
                Text("Ping", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                targets.forEach { (name, ip) ->
                    FilterChip(selected = target == ip, onClick = { target = ip }, label = { Text(name) },
                        modifier = Modifier.padding(start = 8.dp).glowFocus(RoundedCornerShape(8.dp)))
                }
            }
            val ok = samples.filterNotNull()
            val now = samples.lastOrNull()
            val jitter = ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.takeIf { it.isNotEmpty() }?.average()
            val loss = if (samples.isEmpty()) 0 else samples.count { it == null } * 100 / samples.size
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                @Composable fun stat(label: String, v: String, c: Color) = Column {
                    Text(label, color = Hand.Muted, fontSize = 13.sp); Text(v, color = c, fontFamily = Hand.Mono, fontSize = 22.sp)
                }
                stat("Now", now?.let { "%.0f ms".format(it) } ?: "–", if ((now ?: 999.0) < 50) Hand.Good else if ((now ?: 999.0) < 100) Hand.Warn else Hand.Bad)
                stat("Average", ok.takeIf { it.isNotEmpty() }?.average()?.let { "%.0f ms".format(it) } ?: "–", Color.White)
                stat("Jitter", jitter?.let { "±%.0f ms".format(it) } ?: "–", if ((jitter ?: 0.0) < 15) Hand.Good else Hand.Warn)
                stat("Lost", "$loss%", if (loss == 0) Hand.Good else Hand.Bad)
            }
            // The last minute, one point a second; red ticks where a ping got no answer.
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val max = (ok.maxOrNull() ?: 100.0).coerceAtLeast(60.0) * 1.2
                val dx = size.width / 59f
                val path = Path()
                var started = false
                samples.forEachIndexed { i, v ->
                    val x = i * dx
                    if (v == null) { drawLine(Hand.Bad, Offset(x, 0f), Offset(x, size.height), 3f); started = false; return@forEachIndexed }
                    val y = size.height - (v / max * size.height).toFloat()
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawLine(Color(0xFF2A3654), Offset(0f, size.height - (50 / max * size.height).toFloat()),
                    Offset(size.width, size.height - (50 / max * size.height).toFloat()), 2f)        // the 50 ms "good" line
                drawPath(path, Hand.Blue, style = Stroke(width = 5f))
            }
            Text("The gray line is 50 ms: under it feels instant in most online games.", color = Hand.Muted, fontSize = 13.sp)
        }

        NetTestCard(ctx) { testing = it }
        WifiCard(ctx, samples.lastOrNull()?.toInt())
        DnsCard(ctx)

    }
}

@Composable
private fun WifiCard(ctx: Context, ping: Int?) {
    val wifi = ctx.getSystemService(WifiManager::class.java)
    @Suppress("DEPRECATION") val info = wifi.connectionInfo
    val f = info?.frequency ?: 0
    val band = when (f) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> null }
    HandCard(Modifier.fillMaxWidth()) {
        Text("Wi-Fi", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        if (band == null) Text("Not connected", color = Hand.Muted, fontSize = 15.sp)
        else Text("$band  ·  signal ${info.rssi} dBm  ·  link ${info.linkSpeed} Mbps", color = Color.White, fontFamily = Hand.Mono, fontSize = 15.sp)
        Text(Net.tip(band, info?.rssi?.takeIf { f > 0 }, ping), color = Hand.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun DnsCard(ctx: Context) {
    val scope = rememberCoroutineScope()
    val system = remember {
        ctx.getSystemService(ConnectivityManager::class.java).let { cm -> cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull() }
    }
    var results by remember { mutableStateOf<List<Net.DnsResult>?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(privateDns(ctx)) }
    fun setPrivate(host: String?) = scope.launch(Dispatchers.IO) {
        if (host == null) Originals.put(ctx, "global:private_dns_mode", "opportunistic")
        else { Originals.put(ctx, "global:private_dns_specifier", host); Originals.put(ctx, "global:private_dns_mode", "hostname") }
        withContext(Dispatchers.Main) { mode = privateDns(ctx) }
    }
    /**
     * Your network's own DNS and the well-known public providers, 10 lookups each, one at a time so they don't
     * slow each other. Only these: no third-party list of strangers' servers is fetched or probed.
     */
    fun bench() = scope.launch(Dispatchers.IO) {
        val known = Net.DNS_PROVIDERS.map { Triple(it.first, it.second, it.third) }
        val list = listOfNotNull(system?.let { Triple("Your network", it.hostAddress ?: "", null as String?) }) + known
        val out = list.mapIndexed { i, (name, ip, dot) ->
            withContext(Dispatchers.Main) { testing = "Testing ${i + 1} of ${list.size} ($name)…" }
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull()
            val ms = List(10) { addr?.let { a -> Net.dnsMs(a).also { Thread.sleep(40) } } }
            Net.DnsResult(name, ip, dot, ms.filterNotNull(), ms.count { it == null })
        }
        withContext(Dispatchers.Main) { results = Net.rank(out); tested = list.size; testing = null }
    }

    HandCard(Modifier.fillMaxWidth()) {
        Text("DNS benchmark", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("DNS turns names into addresses. A faster one makes games, stores and matchmaking connect sooner; " +
            "it doesn't change in-game ping.", color = Hand.Muted, fontSize = 14.sp)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = { results = null; bench() }, Modifier.glowFocus(RoundedCornerShape(50)), enabled = testing == null) {
                Text(if (results == null) "Run benchmark" else "Run again")
            }
            testing?.let { Text(it, color = Hand.Muted, fontSize = 14.sp) }
        }
        results?.let { all ->
            // The top 10, plus your own DNS wherever it placed, so you can see how it compares.
            val r = all.take(10) + all.drop(10).filter { it.name == "Your network" }
            Text("Tested $tested servers. Fastest first:", color = Hand.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            // Bars span fastest → slowest shown, so a 10 ms gap is visible (from zero they all looked the same).
            val meds = r.mapNotNull { it.median }
            val lo = (meds.minOrNull() ?: 0) * 0.85f; val hi = (meds.maxOrNull() ?: 1).toFloat().coerceAtLeast(lo + 1f)
            val worst = hi.toInt()
            r.forEachIndexed { i, x ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${all.indexOf(x) + 1}. ${x.name}", color = if (i == 0) Hand.Good else Color.White, fontSize = 15.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.3f))
                    // A bar per provider: shorter is faster.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(0.35f).height(14.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((((x.median ?: worst) - lo) / (hi - lo)).coerceIn(0.05f, 1f))
                            .height(14.dp).background(if (i == 0) Hand.Good else Hand.Blue, RoundedCornerShape(7.dp)))
                    }
                    Text("  " + (x.median?.let { "$it ms" } ?: "no answer") + (x.avg?.let { "  avg $it" } ?: "") +
                        (if (x.lost > 0) "  ${x.lost}/10 lost" else ""), color = Hand.Muted, fontFamily = Hand.Mono, fontSize = 14.sp)
                }
            }
            if (all.first().dot == null && all.first().name != "Your network") Text(
                "The fastest, ${all.first().name} (${all.first().ip}), can't be set as Private DNS; you can set it in your router instead.",
                color = Hand.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            val best = all.firstOrNull { it.dot != null && it.median != null }
            if (best != null) Button(onClick = { setPrivate(best.dot) }, Modifier.padding(top = 10.dp).glowFocus(RoundedCornerShape(50))) {
                Text(if (mode == best.name) "${best.name} is in use" else "Use ${best.name} as Private DNS")
            }
            Text("Private DNS needs a server with an encrypted name; only the well-known providers have one, so the rest can be tested but not switched to here.",
                color = Hand.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text("Private DNS: $mode", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Automatic" to null) + Net.DNS_PROVIDERS.filter { it.third != null }.map { it.first to it.third }).forEach { (n, host) ->
                FilterChip(selected = mode.startsWith(n), onClick = { setPrivate(host) }, label = { Text(n) },
                    modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
            }
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
