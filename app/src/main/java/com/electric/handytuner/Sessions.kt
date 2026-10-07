// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One play session per line in sessions.jsonl: which game, how long, how much battery. Last 50 kept. */
object Sessions {
    data class Session(val pkg: String, val label: String, val start: Long, val end: Long, val batStart: Int, val batEnd: Int, val charging: Boolean) {
        val minutes get() = ((end - start) / 60_000).toInt()
        val used get() = batStart - batEnd
        /** %/hour; null for short or charging sessions, where it means nothing. */
        val drainPerHour get() = if (charging || minutes < 5) null else used * 60.0 / minutes
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "sessions.jsonl")

    @Synchronized fun add(ctx: Context, s: Session) {
        if (s.end - s.start < 60_000) return                       // under a minute: a glance, not a session
        val lines = (runCatching { file(ctx).readLines() }.getOrDefault(emptyList()) + JSONObject().apply {
            put("pkg", s.pkg); put("label", s.label); put("start", s.start); put("end", s.end)
            put("batStart", s.batStart); put("batEnd", s.batEnd); put("charging", s.charging)
        }.toString()).takeLast(50)
        file(ctx).writeAtomic(lines.joinToString("\n") + "\n")
    }

    fun all(ctx: Context): List<Session> = runCatching { file(ctx).readLines() }.getOrDefault(emptyList()).mapNotNull { l ->
        runCatching {
            val o = JSONObject(l)
            Session(o.getString("pkg"), o.getString("label"), o.getLong("start"), o.getLong("end"),
                o.getInt("batStart"), o.getInt("batEnd"), o.optBoolean("charging"))
        }.getOrNull()
    }.reversed()
}
