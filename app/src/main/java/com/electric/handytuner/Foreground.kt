// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.io.File

/**
 * The app in front, from Usage Access. Remembered between calls, and looked up
 * 6 h back on first use: Pulse only looked 10 s back, so after a restart
 * mid-game it thought nothing was in front (the bug fixed in the Pulse fork).
 */
class Foreground(private val ctx: Context) {
    private var last: String? = null
    private var seeded = false

    fun current(): String? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return last
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - if (seeded) 15_000 else 6 * 3_600_000L, now)
        seeded = true
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // HandyTuner's own screen counts too: the watcher treats it as neutral, and hides the HUD over it.
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}

/**
 * Which game, not just which app. A Windows-game host (GameNative, Winlator) is
 * one Android app for every game, and a frontend like Cocoon launches them with
 * an intent other apps can't read. The running .exe is visible though, so the id
 * is "app.gamenative:SlimeRancher.exe" once the game is up, and the package
 * itself for everything else.
 */
object GameId {
    val WINDOWS_HOSTS = setOf("app.gamenative", "com.winlator")

    /** Wine, Steam and Windows' own processes, which run beside every game. */
    private val HELPERS = setOf(
        "steam.exe", "steamclient_loader_x64.exe", "steamclient_loader.exe", "steamwebhelper.exe", "steamerrorreporter.exe",
        "explorer.exe", "services.exe", "winedevice.exe", "plugplay.exe", "svchost.exe", "rpcss.exe", "wineboot.exe",
        "conhost.exe", "start.exe", "tabtip.exe", "rundll32.exe", "winhandler.exe", "wfm.exe", "cmd.exe", "rundll.exe",
        "unitycrashhandler64.exe", "unitycrashhandler32.exe", "crashreportclient.exe", "dxsetup.exe", "vc_redist.x64.exe",
    )

    fun of(ctx: Context, pkg: String): String {
        if (pkg !in WINDOWS_HOSTS) return romOf(ctx, pkg)
        val out = PServer.run("sh ${script(ctx)}") ?: return pkg
        return exe(out)?.let { "$pkg:$it" } ?: pkg
    }

    /**
     * Emulators: a frontend like Cocoon hands the emulator the game FILE and grants it read access to exactly that
     * file, and `dumpsys activity permissions` lists the grant ("...document/primary%3AROMs%2Fgc%2FWind%20Waker.iso"
     * for org.dolphinemu.dolphinemu). So the id becomes "org.dolphinemu.dolphinemu:Wind Waker". Asked once per app
     * switch (a different game means going back to the frontend first). No grant = started inside the emulator
     * itself: the package alone, one preset for the whole emulator, as before.
     */
    private var romPkg: String? = null
    private var romId: String? = null
    private fun romOf(ctx: Context, pkg: String): String {
        if (pkg == romPkg) return romId ?: pkg
        romPkg = pkg
        romId = PServer.run("sh ${romsScript(ctx)} $pkg")?.let { rom(it, archivesOk = pkg in GameStore.EMULATORS) }?.let { "$pkg:$it" }
        return romId ?: pkg
    }

    /** Game files only (so a video player or file manager never becomes a "game"); archives only for known emulators. */
    private val ROM_EXT = Regex("""\.(nkit\.iso|iso|rvz|gcm|gcz|wbfs|wia|ciso|cso|chd|cue|bin|pbp|img|m3u|3ds|cia|cci|cxi|nds|gba|gbc|gb|sfc|smc|nes|fds|n64|z64|v64|md|gen|smd|sms|gg|pce|ngp|ngc|ws|wsc|a26|lnx|xci|nsp|wad|dol|elf|rpx|wux|wud|ps2|vpk)$""", RegexOption.IGNORE_CASE)
    private val ARCHIVE_EXT = Regex("""\.(zip|7z)$""", RegexOption.IGNORE_CASE)

    /** "Legend of Zelda, The - The Wind Waker (USA, Canada)" from one roms.sh line, or null if it isn't a game file. */
    fun rom(out: String, archivesOk: Boolean = false): String? {
        val doc = out.trim().substringAfter("document/", "").ifEmpty { return null }
        val name = java.net.URLDecoder.decode(doc.replace("+", "%2B"), "UTF-8").substringAfterLast('/').substringAfterLast(':')
        val ext = ROM_EXT.find(name) ?: (if (archivesOk) ARCHIVE_EXT.find(name) else null) ?: return null
        return name.removeRange(ext.range).ifBlank { null }
    }

    /** "SlimeRancher" for "app.gamenative:SlimeRancher.exe"; "The Legend of Zelda - The Wind Waker" for a ROM. */
    fun label(id: String): String? {
        val raw = id.substringAfter(':', "").ifEmpty { return null }
        if (raw.endsWith(".exe", ignoreCase = true)) return raw.dropLast(4)
        return pretty(raw)
    }

    /** "Legend of Zelda, The - The Wind Waker (USA, Canada) [!]" -> "The Legend of Zelda - The Wind Waker". */
    fun pretty(raw: String): String {
        var t = raw.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace('_', ' ').trim()
        Regex("""^(.+?), (The|A|An)( - |$)(.*)""").find(t)?.let { m ->
            t = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}${m.groupValues[4]}".trim()
        }
        return t.ifBlank { raw }
    }

    private fun romsScript(ctx: Context) = File(ctx.filesDir, "roms.sh").apply {
        if (!exists() || readText() != romsScriptText()) {
            writeText(romsScriptText())
            setReadable(true, false); ctx.filesDir.setExecutable(true, false)
        }
    }.absolutePath

    /** The game's .exe from ";"-joined `ps -A -o ARGS` lines: the last one that isn't a helper. */
    fun exe(out: String): String? = out.split(';').mapNotNull { line ->
        Regex("""([^\\/:]+\.exe)(\s|$)""", RegexOption.IGNORE_CASE).find(line.trim())?.groupValues?.get(1)
    }.lastOrNull { it.lowercase() !in HELPERS }


    private fun script(ctx: Context) = File(ctx.filesDir, "exes.sh").apply {
        if (!exists()) {
            // One line out: PServer replies corrupt when they are long or multi-line.
            writeText(exesScriptText())
            setReadable(true, false); ctx.filesDir.setExecutable(true, false)
        }
    }.absolutePath
}

/**
 * exes.sh: the list of running Wine processes, as one line. Read-only by design — it only lists, so
 * nothing in it can change anything.
 *
 * A function so CommandSafetyTest can read it: only `sh <path>` reaches [PServer.run], so the body
 * of a script never passes through [PServer.unsafeIn].
 */
/**
 * roms.sh: the game file a frontend handed to the emulator ($1 = its package), as one short line. Read-only by
 * design: it only reads Android's list of file grants.
 */
internal fun romsScriptText() = "dumpsys activity permissions | grep -B1 \"targetPkg=\$1\$\" | grep -o \"document/[^ ]*\" | tail -1\n"

internal fun exesScriptText() = "ps -A -o ARGS | grep -iE \"\\.exe( |\$)\" | grep -v grep | tr '\\n' ';'\n"
