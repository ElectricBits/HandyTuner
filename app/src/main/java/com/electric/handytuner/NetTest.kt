// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Network test (D17): lag, lag while downloading, DNS, and Low Latency off vs on, with a verdict in plain
 * words. The run rows stay put while results appear below them, so the D-pad never loses its row.
 * (Network's live ping graph isn't running meanwhile: it's on the page underneath, which is closed.)
 */
@Composable
fun NetTestScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var verdict by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend (say: (String) -> Unit) -> Unit) {
        busy = true; lines = emptyList(); verdict = emptyList()
        scope.launch {
            try { block { s -> scope.launch(Dispatchers.Main) { status = s } } }
            finally { busy = false; status = null }
        }
    }

    ConsolePage {
        RowGroup {
            ActionRow("Test my network", hint = if (busy) status ?: "Working…" else "About a minute, on Wi-Fi only",
                help = "Measures lag to your router and the internet, lag while something downloads, and website lookups, then " +
                    "says what's wrong and how to fix it. Uses about 25 MB.", enabled = !busy, icon = Icons.Rounded.NetworkCheck) {
                run { say -> val r = withContext(Dispatchers.IO) { quick(ctx, say) }; lines = r.first; verdict = r.second }
            }
            ActionRow("Test Low Latency", hint = if (busy) status ?: "Working…" else "About two minutes: off, then on",
                help = "Runs the same lag test with Low Latency mode off and on, so you can see what it does on your Wi-Fi.",
                enabled = !busy, icon = Icons.Rounded.Speed) {
                run { say -> lines = withContext(Dispatchers.IO) { lowLatencyAb(ctx, say) } }
            }
        }
        if (lines.isNotEmpty()) RowGroup("Results") { lines.forEach { (k, v) -> InfoRow(k, v) } }
        if (verdict.isNotEmpty()) RowGroup("What to do") { verdict.forEach { InfoRow(it, "") } }
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
