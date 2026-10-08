// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Network test (D17): lag, lag while downloading, DNS, and Low Latency off vs on, with a verdict in
 * plain words. [onRunning] pauses the page's live ping graph, whose pings would skew the numbers.
 */
@Composable
fun NetTestCard(ctx: Context, onRunning: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var verdict by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend (say: (String) -> Unit) -> Unit) {
        busy = true; onRunning(true); lines = emptyList(); verdict = emptyList()
        scope.launch {
            try { block { s -> scope.launch(Dispatchers.Main) { status = s } } }
            finally { busy = false; onRunning(false); status = null }
        }
    }

    HandCard(Modifier.fillMaxWidth()) {
        Text("Network test", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("Measures lag to your router and the internet, lag while something downloads, and website lookups, then says " +
            "what's wrong and how to fix it. Uses about 25 MB, on Wi-Fi only.", color = Hand.Muted, fontSize = 14.sp)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = { run { say ->
                val r = withContext(Dispatchers.IO) { quick(ctx, say) }
                lines = r.first; verdict = r.second
            } }, modifier = Modifier.glowFocus(RoundedCornerShape(50))) { Text("Test my network (~1 min)") }
            Button(enabled = !busy, onClick = { run { say ->
                lines = withContext(Dispatchers.IO) { lowLatencyAb(ctx, say) }
            } }, modifier = Modifier.glowFocus(RoundedCornerShape(50))) { Text("Test Low Latency (~2 min)") }
        }
        status?.let { Text(it, color = Hand.Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
        lines.forEach { (k, v) ->
            Row(Modifier.padding(top = 6.dp)) {
                Text(k, color = Hand.Muted, fontSize = 14.sp, modifier = Modifier.weight(0.35f))
                Text(v, color = Color.White, fontFamily = Hand.Mono, fontSize = 14.sp, modifier = Modifier.weight(0.65f))
            }
        }
        verdict.forEach { Text("• $it", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun gateway(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway?.address?.size == 4 }
        ?.gateway?.hostAddress ?: "192.168.1.1"
}

private fun onWifi(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

private fun band(ctx: Context): String? {
    @Suppress("DEPRECATION") val f = ctx.getSystemService(WifiManager::class.java).connectionInfo?.frequency ?: 0
    return when (f) { in 2400..2500 -> "2.4G"; in 4900..5900 -> "5G"; in 5925..7125 -> "6G"; else -> null }
}

/** The system DNS server's lookup time, median of 5. */
private fun dns(ctx: Context): Int? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val server = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull { it.address.size == 4 } ?: return null
    return (1..5).mapNotNull { Net.dnsMs(server) }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
}

private fun quick(ctx: Context, say: (String) -> Unit): Pair<List<Pair<String, String>>, List<String>> {
    if (!onWifi(ctx)) return listOf("Wi-Fi" to "not connected") to listOf("Connect to Wi-Fi first: this test is for your Wi-Fi.")
    val gw = gateway(ctx)
    say("Pinging your router…"); val router = NetBench.ping(gw, 100)
    say("Pinging the internet…"); val internet = NetBench.ping("1.1.1.1", 100)
    val game = Net.lastServer(ctx)?.let { (ip, pkg) -> say("Pinging ${GameId.label(pkg) ?: "the game"}'s server…"); (GameId.label(pkg) ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) to NetBench.ping(ip, 50) }
    say("Pinging while downloading…"); val (loaded, mbps) = NetBench.pingUnderLoad("1.1.1.1", 50)
    say("Timing website lookups…"); val dnsMs = dns(ctx)
    val b = band(ctx)
    val lines = listOfNotNull(
        "Router" to router.line, "Internet" to internet.line,
        game?.let { (name, s) -> "Game ($name)" to s.line },
        "While downloading" to loaded.line + (mbps?.let { " · %.0f Mbit/s".format(it) } ?: ""),
        "Lookups (DNS)" to (dnsMs?.let { "$it ms" } ?: "–"),
        "Wi-Fi band" to (b ?: "–"),
    )
    return lines to NetBench.verdict(router, internet, loaded, b, dnsMs)
}

/**
 * Low Latency's Wi-Fi mode off vs on, three rounds each way so Wi-Fi drift hits both sides. The page is in
 * front while it runs, so the lock it takes counts (Android honours it for the app in front).
 */
private fun lowLatencyAb(ctx: Context, say: (String) -> Unit): List<Pair<String, String>> {
    if (!onWifi(ctx)) return listOf("Wi-Fi" to "not connected")
    val gw = gateway(ctx)
    val wifi = ctx.getSystemService(WifiManager::class.java)
    val off = mutableListOf<Double>(); val on = mutableListOf<Double>()
    var sentOff = 0; var sentOn = 0
    repeat(3) { r ->
        say("Round ${r + 1} of 3: Low Latency off…")
        NetBench.parse(pingRaw(gw, 60)).let { off += it; sentOff += 60 }
        val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HandyTuner:nettest").apply { acquire() }
        try {
            Thread.sleep(3_000)
            say("Round ${r + 1} of 3: Low Latency on…")
            NetBench.parse(pingRaw(gw, 60)).let { on += it; sentOn += 60 }
        } finally { lock.release() }
        Thread.sleep(3_000)
    }
    val a = NetBench.stats(off, sentOff); val b = NetBench.stats(on, sentOn)
    val better = b.p95 < a.p95 * 0.8 || b.jitter < a.jitter * 0.8
    return listOf("Off" to a.line, "On" to b.line,
        "Result" to if (better) "Low Latency helps on your Wi-Fi: worst 5%% %.0f → %.0f ms".format(a.p95, b.p95)
            else "No clear difference on your Wi-Fi right now")
}

private fun pingRaw(host: String, count: Int): String {
    val p = ProcessBuilder("/system/bin/ping", "-c", "$count", "-i", "0.2", "-W", "1", host).redirectErrorStream(true).start()
    return try { p.inputStream.bufferedReader().use { it.readText() } } finally { p.destroy() }
}
