// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import com.kei.pulse.root.ShizukuShell

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
        val access = PServer.access()
        return Grants.all.map { g ->
            val on = Grants.granted(ctx, g.kind)
            Check(g.title, if (on) "On" else "Needed so I can ${g.why}", if (on) Status.OK else Status.GRANT,
                Grants.intent(ctx, g.kind))
        } + Check("System access", when (access) {
            PServer.Access.OK -> "On: I can change the Odin's system settings"
            PServer.Access.BLOCKED -> "Blocked: Android won't let me use AYN's service, so TDP is off"
            PServer.Access.MISSING -> "I can't find AYN's system service, the one that changes system settings"
        }, if (access == PServer.Access.OK) Status.OK else Status.MISSING) + listOfNotNull(shizuku(ctx, access))
    }

    /** Marks the fix that asks Shizuku for permission: that's a call, not a screen to open. */
    const val SHIZUKU_ALLOW = "com.electric.handytuner.SHIZUKU_ALLOW"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** Only where AYN's service doesn't work: Shizuku is the way back to the controls the shell user can reach. */
    private fun shizuku(ctx: Context, access: PServer.Access): Check? {
        if (access == PServer.Access.OK) return null
        val what = "charging, fan and display controls"
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        return when {
            ShizukuShell.ready() -> Check("Shizuku", "On: charging, fan and display work through it. Not TDP", Status.OK)
            ShizukuShell.running() -> Check("Shizuku", "Allow HandyTuner in Shizuku to get $what", Status.GRANT, Intent(SHIZUKU_ALLOW))
            launch != null -> Check("Shizuku", "Start Shizuku, then come back here to allow HandyTuner", Status.GRANT, launch)
            else -> Check("Shizuku", "Optional: Shizuku gives back $what, no root needed", Status.GRANT,
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/")))
        }
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
        // Optional: any launcher works, so a missing frontend is still OK.
        val fe = frontend(ctx)
        return listOf(
            Check("PULSE engine", PulseStatus.detail(link), PulseStatus.status(link), pulseFix),
            Check("Game frontend", if (fe != null) "${FRONTENDS[fe]} (v${version(fe)}): emulator games started from it are recognized"
                else "None found (optional): a frontend like Cocoon, Daijisho or ES-DE lets emulator games get their own presets",
                Status.OK),
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
