// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Network test (Network page; docs/benchmarks.md, docs/decisions.md D17): HandyTuner's own network
 * benchmark, the same method as tools/bench/latency.py. Blocking; run it off the main thread.
 */
object NetBench {
    /** One set of pings, summed up. Medians and tails, because spikes are what a player feels. */
    data class Stats(val n: Int, val median: Double, val p95: Double, val p99: Double, val jitter: Double, val lossPct: Double) {
        val line get() = if (n == 0) "no replies" else
            "%.0f ms typical · %.0f ms worst 5%% · ±%.1f · %s lost".format(median, p95, jitter, if (lossPct == 0.0) "0%" else "%.0f%%".format(lossPct))
    }

    fun stats(times: List<Double>, sent: Int): Stats {
        if (times.isEmpty()) return Stats(0, 0.0, 0.0, 0.0, 0.0, 100.0)
        val s = times.sorted()
        fun q(p: Double) = s[minOf(s.size - 1, (p * s.size).toInt())]
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        val jitter = times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().takeIf { !it.isNaN() } ?: 0.0
        return Stats(times.size, median, q(0.95), q(0.99), jitter, 100.0 * (sent - times.size).coerceAtLeast(0) / sent.coerceAtLeast(1))
    }

    /** Every reply time from `ping`'s own output. */
    fun parse(out: String) = Regex("time=([0-9.]+)").findAll(out).map { it.groupValues[1].toDouble() }.toList()

    /** [count] pings 0.2 s apart (the fastest Android allows an app), about count/5 seconds. */
    fun ping(host: String, count: Int): Stats {
        val p = ProcessBuilder("/system/bin/ping", "-c", "$count", "-i", "0.2", "-W", "1", host).redirectErrorStream(true).start()
        val out = try { p.inputStream.bufferedReader().use { it.readText() } } finally { p.destroy() }
        return stats(parse(out), count)
    }

    /**
     * Pings while a download runs: "bufferbloat", a router letting a download swamp game traffic, is a
     * common cause of spikes. Returns the pings under load and the download speed in Mbit/s.
     */
    fun pingUnderLoad(host: String, count: Int, url: String = LOAD_URL): Pair<Stats, Double?> {
        var bytes = 0L; var ms = 0L
        val t = thread(isDaemon = true) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5_000; c.readTimeout = 10_000
                val start = System.nanoTime()
                c.inputStream.use { s -> val buf = ByteArray(64 * 1024); while (true) { val r = s.read(buf); if (r < 0) break; bytes += r } }
                ms = (System.nanoTime() - start) / 1_000_000
                c.disconnect()
            }
        }
        Thread.sleep(1_000)                                          // let the download get going
        val s = ping(host, count)
        t.join(30_000)
        return s to (if (ms > 0) bytes * 8.0 / 1_000_000 / (ms / 1000.0) else null)
    }

    /** What the numbers mean, and what to do: plain words, most important first. */
    fun verdict(router: Stats, internet: Stats, loaded: Stats?, band: String?, dnsMs: Int?): List<String> {
        val out = mutableListOf<String>()
        if (router.lossPct >= 2 || internet.lossPct >= 2) out += "Packets are being lost (${internet.lossPct.toInt()}%): games will rubber-band. Move closer to the router or use 5 GHz."
        if (router.p95 > 20 || router.jitter > 3) out += "Your Wi-Fi has lag spikes (worst ${router.p99.toInt()} ms to the router). Low Latency fixes most of this — turn it on for online games."
        if (band == "2.4G") out += "You're on 2.4 GHz Wi-Fi: slower and busier. Switch to your router's 5 GHz network if it has one."
        if (loaded != null && loaded.n > 0 && loaded.p95 - internet.median > 60)
            out += "Lag jumps by ${(loaded.p95 - internet.median).toInt()} ms while something downloads (bufferbloat). Avoid downloads while playing, or turn on QoS/SQM on your router."
        if (internet.median > 80) out += "The internet itself is slow to reach (${internet.median.toInt()} ms typical). That's your connection or distance, not the Odin."
        if (dnsMs != null && dnsMs > 60) out += "Website lookups are slow (${dnsMs} ms). Run the DNS benchmark below and use the fastest one."
        if (out.isEmpty()) out += "All good: low lag, no spikes, nothing lost."
        return out
    }

    /** Cloudflare's speed-test endpoint: 25 MB, Wi-Fi only (the page refuses to run on mobile data). */
    const val LOAD_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
}
