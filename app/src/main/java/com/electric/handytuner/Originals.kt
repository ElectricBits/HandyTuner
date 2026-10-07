// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Every system setting HandyTuner changes, as it was before the first change.
 * Saved once per key and kept on disk, so a crash or a kill still leaves the
 * way back; [restore] puts keys back and forgets them. This is what "Reset
 * everything to stock" runs.
 *
 * Keys are "namespace:key" ("global:wifi_scan_always_enabled"); a bare key is
 * in the system table.
 */
object Originals {
    private fun file(ctx: Context) = File(ctx.filesDir, "originals.properties")

    private fun read(ctx: Context) = Properties().apply { runCatching { file(ctx).inputStream().use { load(it) } } }

    private fun split(k: String) = if (':' in k) k.substringBefore(':') to k.substringAfter(':') else "system" to k

    /** Call before writing a setting. Only the first call per key records anything. */
    @Synchronized fun remember(ctx: Context, key: String) = FileGuard.locked(file(ctx)) {
        val p = read(ctx)
        if (p.containsKey(key)) return@locked
        val (ns, k) = split(key)
        // A blank reply means we couldn't read the setting, and remembering "" would restore an
        // empty value later — worse than not remembering it at all.
        val before = PServer.run("settings get $ns $k")?.takeIf { it.isNotBlank() } ?: return@locked
        p.setProperty(key, before)
        file(ctx).storeAtomic(p)
    }

    /** remember() then write. */
    fun put(ctx: Context, key: String, value: String) {
        remember(ctx, key)
        val (ns, k) = split(key)
        PServer.run("settings put $ns $k $value")
    }

    @Synchronized fun restore(ctx: Context, keys: Collection<String>? = null) = FileGuard.locked(file(ctx)) {
        val p = read(ctx)
        for (key in (keys ?: p.stringPropertyNames()).toList()) {
            val v = p.getProperty(key) ?: continue
            val (ns, k) = split(key)
            if (v == "null") PServer.run("settings delete $ns $k") else PServer.run("settings put $ns $k $v")
            p.remove(key)
        }
        file(ctx).storeAtomic(p)
    }

    fun saved(ctx: Context): Set<String> = read(ctx).stringPropertyNames()
}
