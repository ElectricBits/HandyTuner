// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.kei.pulse.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

/**
 * Runs shell commands through Shizuku: the way in when AYN's PServerBinder is blocked (the Odin 2 Mini,
 * HandyTuner issue #4). Without root, Shizuku runs as Android's `shell` user, which is much weaker than
 * PServer's root: settings, `wm`, `cmd`, and on the Odin the charging and fan nodes work, but the CPU and
 * GPU speed nodes are owned by root/system, so TDP stays PServer-only (measured on the Portal and the Mini).
 */
object ShizukuShell {
    @Volatile private var app: Context? = null
    @Volatile private var askedAt = 0L

    /** From Application.onCreate in every process. Only the provider's process passes the binder on. */
    fun init(ctx: Context, providerProcess: Boolean) {
        ShizukuProvider.enableMultiProcessSupport(providerProcess)
        if (!providerProcess) app = ctx.applicationContext
    }

    /** Shizuku is running and HandyTuner may use it. */
    fun ready(): Boolean = running() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku is running, so asking for permission makes sense. */
    fun running(): Boolean {
        val up = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!up) askForBinder()
        return up
    }

    /**
     * Outside the main process the binder has to be fetched from it. Asked at most once a minute, since
     * Shizuku may be started (or HandyTuner's main process may come up) after the first try.
     */
    private fun askForBinder() {
        val ctx = app ?: return
        val now = System.currentTimeMillis()
        if (now - askedAt < 60_000) return
        askedAt = now
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(ctx) }
    }

    /**
     * `adb shell setprop debug.handytuner.shizuku 1` makes HandyTuner act as if PServer were blocked, so the
     * Shizuku path can be tested on an Odin where PServer works. Only adb can set debug.* properties.
     */
    @android.annotation.SuppressLint("PrivateApi")
    fun forced(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.handytuner.shizuku") == "1"
    }.getOrDefault(false)

    /** Shows Shizuku's own allow/deny dialog. */
    fun requestPermission() { runCatching { Shizuku.requestPermission(0) } }

    /** Like PServer: stdout trimmed, or null if it couldn't run. */
    fun run(cmd: String): String? = runCatching {
        // Shizuku 13 made Shizuku.newProcess private; its service interface still has it.
        val p = IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(arrayOf("sh"), null, null)
        ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream).use {
            // stderr goes nowhere: nobody reads it, and a full pipe would stall the command.
            it.write("exec 2>/dev/null\n${stdin(cmd) { path -> runCatching { File(path).readText() }.getOrNull() }}\n".toByteArray())
        }
        val out = ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).use { it.readBytes() }
        p.waitFor()
        runCatching { p.errorStream.close() }
        out.toString(Charsets.UTF_8).trim()
    }.onFailure { Log.w("HandyTuner", "shizuku: $cmd: $it") }.getOrNull()

    /**
     * What to feed `sh` for [cmd]. A `sh <script> args` call becomes the script's own text in a here-document,
     * because the shell user can't read HandyTuner's private files the way PServer's root can. Whatever follows
     * the arguments (`&& echo token` from PServer.ok) stays after the here-document marker, where sh expects it.
     */
    internal fun stdin(cmd: String, read: (String) -> String?): String {
        val m = SCRIPT.matchEntire(cmd) ?: return cmd
        val (path, args, rest) = m.destructured
        val body = read(path) ?: return cmd
        return "sh -s$args <<'$EOF'$rest\n${body.trimEnd('\n')}\n$EOF"
    }

    private const val EOF = "__HT_EOF"
    /** `sh /abs/path`, then plain arguments, then anything from the first operator on. */
    private val SCRIPT = Regex("""sh (/\S+)((?: [^&|;<>\s]+)*)(.*)""", RegexOption.DOT_MATCHES_ALL)
}
