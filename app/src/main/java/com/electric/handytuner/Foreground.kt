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
        if (pkg !in WINDOWS_HOSTS) return pkg
        val out = PServer.run("sh ${script(ctx)}") ?: return pkg
        return exe(out)?.let { "$pkg:$it" } ?: pkg
    }

    /** The game's .exe from ";"-joined `ps -A -o ARGS` lines: the last one that isn't a helper. */
    fun exe(out: String): String? = out.split(';').mapNotNull { line ->
        Regex("""([^\\/:]+\.exe)(\s|$)""", RegexOption.IGNORE_CASE).find(line.trim())?.groupValues?.get(1)
    }.lastOrNull { it.lowercase() !in HELPERS }

    /** "SlimeRancher" for "app.gamenative:SlimeRancher.exe". */
    fun label(id: String) = id.substringAfter(':', "").removeSuffix(".exe").removeSuffix(".EXE").ifEmpty { null }

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
internal fun exesScriptText() = "ps -A -o ARGS | grep -iE \"\\.exe( |\$)\" | grep -v grep | tr '\\n' ';'\n"
