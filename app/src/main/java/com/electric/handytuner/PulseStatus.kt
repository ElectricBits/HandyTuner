// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.util.Log
import java.io.File

/**
 * What the overlay learned about the Pulse fork, handed to the rest of the app.
 *
 * The link only exists in the `:overlay` process (that's where [PulseLink] lives), but Diagnostics runs in
 * the main process, which can't see another process's memory. So the overlay leaves a small note in
 * HandyTuner's **own** files directory — no root, no permissions, same app, and the fork's
 * `/data/data` stays unreadable to us on purpose. The note is only a hint: it goes stale when the overlay
 * stops, and [read] says so instead of pretending to know.
 */
object PulseStatus {
    const val FILE = "pulse_status"
    private const val TAG = "HandyTuner"

    /**
     * Older than this and the overlay isn't there to refresh it, so the note means nothing. Generous,
     * because the overlay only writes when the link answers (no timer in an overlay): a long quiet stretch
     * is normal and must not look like the overlay being gone.
     */
    const val STALE_MS = 10 * 60_000L

    data class State(val linked: Boolean, val version: Int, val watcherAsleep: Boolean, val at: Long)

    fun encode(s: State) = buildString {
        append("linked=").append(s.linked).append('\n')
        append("version=").append(s.version).append('\n')
        append("watcher=").append(if (s.watcherAsleep) "asleep" else "awake").append('\n')
        append("at=").append(s.at)
    }

    /**
     * Parses a note, or null if it isn't one. Every field has to be there and make sense: a note caught
     * half-written (the overlay is a second process writing while this one reads) is no evidence at all,
     * so guessing from a missing field would be worse than saying nothing.
     */
    fun decode(text: String?): State? {
        val fields = text?.lineSequence()?.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.take(i) to line.substring(i + 1)
        }?.toMap() ?: return null
        val linked = fields["linked"]?.takeIf { it == "true" || it == "false" } ?: return null
        val watcher = fields["watcher"]?.takeIf { it == "awake" || it == "asleep" } ?: return null
        val at = fields["at"]?.toLongOrNull() ?: return null
        if (at <= 0) return null
        return State(linked = linked == "true", version = fields["version"]?.toIntOrNull() ?: 0,
            watcherAsleep = watcher == "asleep", at = at)
    }

    /** The overlay's latest note, or null when there is none or it's too old to trust. */
    fun read(ctx: Context, now: Long = System.currentTimeMillis()): State? =
        fresh(decode(runCatching { File(ctx.filesDir, FILE).readText() }.getOrNull()), now)

    /** A note is only as good as its age. A note from the future is treated as untrustworthy too. */
    fun fresh(state: State?, now: Long): State? = state?.takeIf { now - it.at in 0..STALE_MS }

    /** Leaves the note. Never throws: the overlay can't afford to die over a status line. */
    fun write(ctx: Context, s: State) {
        runCatching { File(ctx.filesDir, FILE).writeText(encode(s)) }
            .onFailure { Log.w(TAG, "pulse status not written: $it") }
    }

    /**
     * The wording for Diagnostics' Pulse row, in one place so the row and the overlay's Quick Menu message
     * can't disagree (docs/BUGS.md #14). [state] is null when the overlay never reported or its note is
     * stale, which is a different thing from "watcher asleep" and gets different words.
     */
    fun detail(state: State?): String = when {
        state == null -> "Not started yet: it starts with HandyTuner's overlay"
        !state.linked -> "Not answering: open HandyTuner so it can reconnect"
        // The engine is built in and its per-game watcher is on by default, so asleep means no Usage access.
        state.watcherAsleep -> "Its watcher is asleep: give HandyTuner Usage access so it can see your games"
        else -> "Running and watching games"
    }

    /** Whether the user still has to do something about the engine. */
    fun status(state: State?): Diagnostics.Status =
        if (state != null && state.linked && !state.watcherAsleep) Diagnostics.Status.OK else Diagnostics.Status.MISSING
}