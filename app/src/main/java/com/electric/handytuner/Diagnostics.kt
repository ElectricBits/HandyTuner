// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent

/**
 * Diagnostics only: the permissions and access HandyTuner needs, and the apps
 * it works with, read live. (Feature checks like ping or FPS were dropped: the
 * owner wanted this page to be about what's granted.)
 *
 * Every permission comes from [Grants], so this page and the setup guide always offer the same page for
 * the same permission (docs/BUGS.md #15).
 */
object Diagnostics {
    enum class Status(val mark: String) { OK("✅"), MISSING("❌"), GRANT("🔑") }
    data class Check(val name: String, val detail: String, val status: Status, val fix: Intent? = null)

    fun setup(ctx: Context): List<Check> {
        val root = PServer.run("echo ok") == "ok"
        return Grants.all.map { g ->
            val on = Grants.granted(ctx, g.kind)
            Check(g.title, if (on) "On" else "Needed so I can ${g.why}", if (on) Status.OK else Status.GRANT,
                Grants.intent(ctx, g.kind))
        } + Check("System access", if (root) "AYN's built-in service answers" else "AYN's built-in service isn't answering",
            if (root) Status.OK else Status.MISSING)
    }

    /**
     * The apps HandyTuner works alongside: installed, or where to get them.
     *
     * PULSE's engine is built in, so its row reports the link [PulseStatus] carries from the overlay instead.
     */
    fun apps(ctx: Context): List<Check> {
        fun version(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()
        fun installed(p: String) = version(p) != null
        fun web(u: String) = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))
        // Read the overlay's note once: it's one small file, and this page is read often.
        val link = PulseStatus.read(ctx)
        // PULSE is built in (merge-plan.md), so the only fixes are the grants it runs on.
        val pulseFix = when {
            !Grants.granted(ctx, Grants.Kind.ACCESSIBILITY) -> Grants.intent(ctx, Grants.Kind.ACCESSIBILITY)
            !Grants.granted(ctx, Grants.Kind.USAGE) -> Grants.intent(ctx, Grants.Kind.USAGE)
            else -> null
        }
        val cocoon = version(COCOON)
        return listOf(
            Check("PULSE engine", PulseStatus.detail(link), PulseStatus.status(link), pulseFix),
            Check("Cocoon", if (cocoon != null) "Installed (v$cocoon): games started from it are recognized"
                else "Not installed: a launcher whose games I recognize",
                if (cocoon != null) Status.OK else Status.GRANT, web("https://github.com/inssekt/CocoonFE/releases")),
        )
    }

    /**
     * Saves HandyTuner's own log (every process: app, :overlay, :pulse — Android only lets an app read its own
     * lines, so nothing from other apps ends up in it) plus a short header to Downloads, for bug reports.
     * Returns the file name, or null if it couldn't be saved.
     */
    fun exportLog(ctx: Context): String? = runCatching {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val name = "HandyTuner-log-$stamp.txt"
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime")).inputStream.bufferedReader().use { it.readText() }
        val rows = (setup(ctx) + apps(ctx)).joinToString("\n") { "${it.status} ${it.name}: ${it.detail}" }
        val tmp = java.io.File(ctx.filesDir, "log-export.txt")
        tmp.writeText("HandyTuner $version on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE}\n\n$rows\n\n$log")
        tmp.setReadable(true, false)
        name.takeIf { PServer.ok("cp ${tmp.absolutePath} /sdcard/Download/$name") }.also { tmp.delete() }
    }.getOrNull()
}
