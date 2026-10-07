// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File

/**
 * GAME latency (to the server the foreground game is connected to) and NET
 * latency (a short test every minute, so offline games still get a number).
 *
 * The server is found from the game's own sockets in /proc/net, read through
 * PServer because an app may only see its own. Games that send UDP without
 * connecting a socket don't show a remote address there, so no server is found
 * and the HUD says "no server" - which is what it means, and does not claim the
 * game is offline. A game reached that way is playing perfectly well.
 */
class Net(private val ctx: Context) {
    data class Latency(val ms: Int?, val jitter: Int?, val loss: Int)

    var server: String? = null; private set
    private val front = Foreground(ctx)
    private val gamePings = ArrayDeque<Double?>()
    private val script by lazy {
        File(ctx.filesDir, "sockets.sh").apply {
            // One line out: PServer replies corrupt when they are long or multi-line.
            writeText("for f in tcp tcp6 udp udp6; do awk -v u=\$1 -v f=\$f '\$8==u {print f, \$3, \$4}' /proc/net/\$f; done | head -60 | tr '\\n' ';'\n")
            setReadable(true, false); ctx.filesDir.setExecutable(true, false)
        }.absolutePath
    }

    /** Re-pick the game server. Call every ~10 s. */
    fun detect() {
        val pkg = front.current() ?: run { server = null; return }
        val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: run { server = null; return }
        val out = PServer.run("sh $script $uid") ?: run { server = null; return }
        server = pick(out)
        // Remembered for the Network page, which runs when no game is in front.
        server?.let { runCatching { File(ctx.filesDir, "game_server").writeText("$it $pkg") } }
    }

    fun pingGame(): Latency? {
        val host = server ?: run { gamePings.clear(); return null }
        gamePings.addLast(pingOnce(host)); if (gamePings.size > 10) gamePings.removeFirst()
        val ok = gamePings.filterNotNull()
        return Latency(ok.lastOrNull()?.toInt(), ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            gamePings.count { it == null } * 100 / gamePings.size)
    }

    /** The once-a-minute test: 5 pings 0.2 s apart, about a second. Latency only, no download. */
    fun netTest(host: String = "1.1.1.1"): Latency {
        val out = ping("-c", "5", "-i", "0.2", "-W", "1", host)
        val rtt = Regex("= ([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)").find(out)?.groupValues
        val loss = Regex("([0-9]+)% packet loss").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 100
        return Latency(rtt?.get(2)?.toDouble()?.toInt(), rtt?.get(4)?.toDouble()?.toInt(), loss)
    }

    fun pingOnce(host: String) = ping("-c", "1", "-W", "1", host)
        .let { Regex("time=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDouble() }

    /**
     * Run ping and take its output.
     *
     * Two things the obvious `exec(...).inputStream.readText()` gets wrong, both of which add up
     * while the HUD is up and pings a couple of times a minute (docs/BUGS.md #7): the stream and the
     * [Process] were never closed, so descriptors and processes piled up; and stderr was piped but
     * never read, so a chatty ping would block on a full stderr pipe forever. Merging the streams
     * removes the deadlock, and the process is destroyed and reaped either way.
     */
    private fun ping(vararg args: String): String {
        val p = runCatching {
            ProcessBuilder("/system/bin/ping", *args).redirectErrorStream(true).start()
        }.getOrNull() ?: return ""
        return try {
            p.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        } finally {
            runCatching {
                p.destroy()
                if (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        }
    }

    /** One resolver's DNS benchmark: [ms] are the answered lookups, [lost] the unanswered ones. */
    data class DnsResult(val name: String, val ip: String, val dot: String?, val ms: List<Int>, val lost: Int) {
        val median get() = ms.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val avg get() = if (ms.isEmpty()) null else ms.average().toInt()
    }

    companion object {
        /** Fastest first: fewest failures, then lowest median (steadier than the average for a few spiky lookups). */
        fun rank(results: List<DnsResult>) = results.sortedWith(compareBy({ it.lost }, { it.median ?: Int.MAX_VALUE }))

        /** Providers to benchmark, with their Private DNS (DNS-over-TLS) name when they have one. */
        val DNS_PROVIDERS = listOf(
            Triple("Cloudflare", "1.1.1.1", "one.one.one.one"), Triple("Google", "8.8.8.8", "dns.google"),
            Triple("Quad9", "9.9.9.9", "dns.quad9.net"), Triple("AdGuard", "94.140.14.14", "dns.adguard-dns.com"),
            Triple("Mullvad", "194.242.2.2", "dns.mullvad.net"),
            Triple("OpenDNS", "208.67.222.222", null), Triple("Control D", "76.76.2.0", null),
        )

        /** The last game server the overlay saw, with its app: "1.2.3.4 com.some.game". */
        fun lastServer(ctx: Context): Pair<String, String>? = runCatching {
            File(ctx.filesDir, "game_server").readText().trim().split(' ').let { it[0] to it.getOrElse(1) { "" } }
        }.getOrNull()

        /** One plain tip, most useful first. Shared by Home and Network. */
        fun tip(band: String?, rssi: Int?, ping: Int?) = when {
            band == null -> "Connect to Wi-Fi for online games."
            (rssi ?: 0) < -70 -> "Weak signal: move closer to your router."
            band.startsWith("2.4") -> "On 2.4 GHz: 5 GHz is usually faster, if your router has it."
            (ping ?: 0) >= 100 -> "Slow internet right now; try Low Ping in the Quick Menu."
            else -> "Good connection."
        }

        /**
         * One DNS lookup straight to [server], timed in ms; null if no answer in 2 s. The name is
         * random each time, so no cache anywhere can answer for it.
         */
        fun dnsMs(server: java.net.InetAddress, timeoutMs: Int = 2_000): Int? = runCatching {
            val name = "ht${System.nanoTime() % 1_000_000}.example.com"
            val q = java.io.ByteArrayOutputStream().apply {
                write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))      // id, recursion desired, 1 question
                name.split('.').forEach { l -> write(l.length); write(l.toByteArray()) }
                write(byteArrayOf(0, 0, 1, 0, 1))                                       // end, type A, class IN
            }.toByteArray()
            java.net.DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val t = System.nanoTime()
                sock.send(java.net.DatagramPacket(q, q.size, server, 53))
                sock.receive(java.net.DatagramPacket(ByteArray(512), 512))
                ((System.nanoTime() - t) / 1_000_000).toInt()
            }
        }.getOrNull()

        /**
         * "proto remote state;..." from /proc/net → the game server's IPv4, or null.
         * Connected UDP first (how games talk), then established TCP. Skips DNS/NTP/mDNS, web
         * (80/443), and Steam's own client ports 27017-27037, which GameNative always holds open.
         */
        fun pick(out: String): String? {
            val found = out.split(';').mapNotNull { line ->
                val (proto, remote, state) = line.trim().split(Regex("\\s+")).takeIf { it.size == 3 } ?: return@mapNotNull null
                val (hex, portHex) = remote.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val port = portHex.toIntOrNull(16) ?: return@mapNotNull null
                val ip = ipv4(hex) ?: return@mapNotNull null
                val udp = proto.startsWith("udp")
                if (port == 0 || port in setOf(53, 123, 5353, 80, 443) || port in 27017..27037) return@mapNotNull null
                if (!udp && state != "01") return@mapNotNull null          // TCP must be ESTABLISHED
                if (!public(ip)) return@mapNotNull null
                Pair(udp, ip)
            }
            return (found.firstOrNull { it.first } ?: found.firstOrNull())?.second
        }

        /** /proc/net addresses are little-endian hex; IPv4-mapped IPv6 keeps the v4 in the last 8 digits. */
        fun ipv4(hex: String): String? {
            val v4 = when {
                hex.length == 8 -> hex
                hex.length == 32 && hex.startsWith("0000000000000000FFFF0000") -> hex.substring(24)
                else -> return null
            }
            return (3 downTo 0).joinToString(".") { v4.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        }

        private fun public(ip: String): Boolean {
            val p = ip.split('.').map { it.toInt() }
            return !(p[0] == 10 || p[0] == 127 || p[0] == 0 || (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) || p[0] >= 224)
        }
    }
}
