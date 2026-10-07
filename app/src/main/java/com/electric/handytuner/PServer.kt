// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * AYN's PServerBinder system service runs a shell command with system rights.
 * Transaction 0 takes [command, "1"] and replies with the output as bytes.
 * From Portal Tuner, measured on the Odin 2 Portal.
 *
 * Commands of 256+ characters are dropped silently. Portal Tuner saw single
 * quotes mishandled while Pulse's quoted commands work, so avoid them anyway;
 * anything longer than a line goes in a script file.
 */
object PServer {
    private const val MAX = 256

    private val binder: IBinder? = runCatching {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "PServerBinder") as IBinder?
    }.getOrNull()

    /** null means the command did not run, or ran and produced nothing — see [ok]. */
    fun run(cmd: String): String? {
        val b = binder ?: return null
        if (cmd.length >= MAX) { Log.w(TAG, "pserver: dropped, ${cmd.length} chars"); return null }
        unsafeIn(cmd)?.let { Log.w(TAG, "pserver: refused, $it"); return null }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            b.transact(0, data, reply, 0)
            reply.createByteArray()?.toString(Charsets.UTF_8)?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "pserver: $cmd: $e")
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /**
     * Whether [cmd] really succeeded — for the commands whose result the user is told about.
     *
     * A command that writes something and prints nothing (`cp`, `screencap`, `settings put`) cannot
     * be judged from its output. [run] returns null for "the command never ran" but also, depending
     * on whether PServerBinder always writes a reply array, for "it ran and said nothing" — so
     * `!= null` was right only by luck, and would report a good export as failed (or a bad one as
     * saved). Letting the shell decide removes the ambiguity: the token comes back only if
     * everything before the `&&` succeeded, whatever the binder does with an empty reply.
     *
     * Use [run] instead where the output itself is the answer (`settings get`, `cat`).
     */
    fun ok(cmd: String): Boolean {
        if (cmd.length + SUFFIX.length >= MAX) { Log.w(TAG, "pserver: too long to confirm, ${cmd.length} chars"); return false }
        return confirmed(run(cmd + SUFFIX))
    }

    /** [ok]'s rule on its own, so it can be tested without a binder. */
    internal fun confirmed(reply: String?) = reply != null && reply.trim().endsWith(TOKEN)

    /**
     * Why [cmd] must not run, or null if it may. Checked here because this is the one place every
     * command passes through — the individual actions can't each be trusted to remember.
     *
     * Two rules. The never-touch nodes would brick, un-brick or corrupt the Odin rather than slow
     * it (docs/device-capabilities.md); nothing HandyTuner does needs them, and it writes no sysfs
     * nodes at all, so a refusal can't break a working feature. And PServerBinder is HandyTuner's only
     * way back in with system rights, so a command that kills it leaves the device with settings
     * changed and no way to undo them. `pkill -f` goes with it: `-f` matches full command lines, and
     * that once matched PServer's own shell.
     */
    internal fun unsafeIn(cmd: String): String? = when {
        NEVER_TOUCH.any { it in cmd } -> "touches ${NEVER_TOUCH.first { it in cmd }}"
        // Any -f among pkill's flags, whatever order they're in. Written as "pkill then -f" rather
        // than a flags list because `f` is itself a single-letter flag, so a repeated-flag pattern
        // swallows the very -f it is looking for.
        Regex("""\bpkill\b[^|;&]*\s-f\b""").containsMatchIn(cmd) -> "pkill -f"
        Regex("""\bpservice\b""").containsMatchIn(cmd) -> "pservice"
        else -> null
    }

    /** Node names no command may contain. Matched as substrings, so `cat`ing one is refused too. */
    internal val NEVER_TOUCH = listOf(
        "ship_mode_en",   // powers the device into shipping mode; needs a long press to leave
        "force_5v",       // forces 5 V on the battery
        "fake_soc",       // spoofs the charge curve
        "fake_soh",       // spoofs state of health
        "wireless_fw",    // firmware update state
        "usb_typec_disable"
    )

    private const val TAG = "HandyTuner"
    private const val TOKEN = "__ht_ok"
    private val SUFFIX = " && echo $TOKEN"
}
