// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Watchdog for an overlay that keeps dying.
 *
 * HandyTuner writes system settings and Pulse commands continuously, so an overlay in a crash loop is
 * worse than one that isn't running: it can leave the device half-configured on the way down. Three
 * starts inside ten minutes is the shape of a loop (the accessibility service being restarted; not
 * low-memory kills, see [recordStart]), never of normal use — the overlay runs for hours at a time once started.
 *
 * On the third start it puts everything back ([trip]) and then the overlay stops writing altogether
 * ([active]) until the owner clears it by hand. Sticky on purpose: a watchdog that clears itself on
 * the next start would re-arm itself during the very crash loop it exists to stop, and a setting
 * somebody has to go and find is the only kind worth trusting here.
 *
 * Everything it does is undoing work, never new work — see docs/BUGS.md #7.
 */
object SafeMode {
    private const val WINDOW_MS = 10 * 60_000L
    private const val LIMIT = 3
    private const val STARTS = "service_starts"
    private const val FLAG = "safe_mode"
    private const val SINCE = "safe_mode_since"

    /**
     * [recordStart]'s rule on its own, so it can be tested without a Context.
     *
     * The window is `[now - windowMs, now]`, closed at both ends. The upper bound is not paranoia:
     * without it a timestamp from the future — the clock corrected backwards, or a corrupted file —
     * has a negative age, passes "is it recent?", and counts towards the limit. Since tripping safe
     * mode puts the device back and then refuses to touch it until the owner clears it, a false trip
     * costs the owner a good deal more than a missed one.
     */
    fun tripAfter(starts: List<Long>, now: Long, windowMs: Long = WINDOW_MS, limit: Int = LIMIT) =
        starts.filter { it <= now && now - it < windowMs }.plus(now).size >= limit

    private fun recent(starts: List<Long>, now: Long) = starts.filter { it <= now && now - it < WINDOW_MS }

    /**
     * Records a service start and says whether this one should trip safe mode.
     *
     * The window is kept in a file rather than memory because the crash loop this watches for is
     * exactly the case where the process doesn't survive to remember anything: three starts across
     * three processes would otherwise look like three innocent ones.
     */
    fun recordStart(ctx: Context): Boolean {
        // Android closing the overlay to free memory isn't a crash loop: loading a big game killed it three
        // times in seconds (with Google services, the keyboard and the launcher), which tripped safe mode and
        // stopped HandyTuner mid-game. Those starts don't count; a crash still does.
        if (diedForMemory(ctx)) return false
        val now = System.currentTimeMillis()
        val p = Properties()
        runCatching { File(ctx.filesDir, STARTS).inputStream().use { p.load(it) } }
        val installed = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L).toString()
        val starts = startsToCount(p.getProperty("t").orEmpty().split(',').mapNotNull { it.toLongOrNull() }, p.getProperty("u"), installed)
        val trip = tripAfter(starts, now)
        p.setProperty("t", (recent(starts, now) + now).joinToString(","))
        p.setProperty("u", installed)
        runCatching { File(ctx.filesDir, STARTS).storeAtomic(p) }
        return trip
    }

    /** Whether the overlay's last death was Android freeing memory (the low-memory killer). */
    private fun diedForMemory(ctx: Context) = runCatching {
        ctx.getSystemService(android.app.ActivityManager::class.java)
            .getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            .firstOrNull { it.processName == "${ctx.packageName}:overlay" }
            ?.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
    }.getOrDefault(false)

    /**
     * An app update restarts the overlay too, and three installs in ten minutes tripped safe mode on the
     * Odin (2026-09-30) with nothing wrong. So starts from before the current install don't count.
     */
    internal fun startsToCount(starts: List<Long>, storedInstall: String?, currentInstall: String) =
        if (storedInstall == currentInstall) starts else emptyList()

    /** Whether the overlay is currently allowed to change anything. */
    fun active(ctx: Context) = File(ctx.filesDir, FLAG).exists()

    /** When safe mode was entered, for the owner to read, or null when it isn't on. */
    fun since(ctx: Context): Long? =
        if (!active(ctx)) null else
        runCatching { Properties().apply { File(ctx.filesDir, FLAG).inputStream().use { load(it) } }.getProperty(SINCE)?.toLongOrNull() }.getOrNull()

    fun set(ctx: Context, on: Boolean, why: String = "manual") {
        val f = File(ctx.filesDir, FLAG)
        if (!on) { f.delete(); return }
        if (!f.exists()) {
            val p = Properties()
            p.setProperty(SINCE, System.currentTimeMillis().toString())
            p.setProperty("why", why)
            runCatching { f.storeAtomic(p) }
        }
    }

    /** Why safe mode is on, in words for the owner. */
    fun why(ctx: Context): String {
        val p = Properties()
        runCatching { File(ctx.filesDir, FLAG).inputStream().use { p.load(it) } }
        return p.getProperty("why") ?: "turned on by hand"
    }
}