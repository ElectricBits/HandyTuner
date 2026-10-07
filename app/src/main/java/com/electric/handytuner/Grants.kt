// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Every one-time permission HandyTuner asks for, in one place: what it buys, the exact Settings
 * labels a person will read on screen, and the words HandyHelper says (docs/UI_BRIEF.md, "HandyHelper's
 * voice"). Onboarding and Diagnostics both read this table, so a button can't point at a page that
 * doesn't exist on this Android version, and the two pages can't drift apart again (docs/BUGS.md #15).
 *
 * Only the settings HandyTuner can actually open live here. The apps it works alongside (Pulse, Cocoon)
 * are rows in [Diagnostics.apps], not grants.
 */
object Grants {
    enum class Kind { ACCESSIBILITY, USAGE, NOTIFICATIONS }

    data class Grant(
        val kind: Kind,
        val title: String,
        val why: String, // lowercase verb phrase, so callers can say "so I can ${g.why}"
        val path: String,
        val steps: List<String>,
        val cheer: String,
        val button: String,
    )

    /** In the order the setup guide asks for them: what makes HandyHelper itself work, then what it adds. */
    val all = listOf(
        Grant(
            Kind.ACCESSIBILITY, "Overlay & hotkeys",
            "draw the HUD over your game and hear your hotkeys",
            "Settings → Accessibility → HandyTuner overlay",
            listOf(
                "First I need to draw over your game and listen for your hotkeys.",
                "Tap the button, then find \"HandyTuner overlay\" in the list.",
                "Switch it on, then come back to me.",
            ),
            "That's it! I can show the HUD over any game now.", "Open Accessibility",
        ),
        Grant(
            Kind.USAGE, "Usage access",
            "tell which game you're playing — that's how per-game profiles and GAME ping work",
            "Settings → Apps → HandyTuner → Usage access",
            listOf(
                "Now let me see which game you're playing.",
                "Then profiles follow that game by themselves, and GAME ping can find its server.",
                "Turn on \"Usage access\" for HandyTuner, then come back.",
            ),
            "Nice! I'll know which game is on.", "Open Usage access",
        ),
        Grant(
            Kind.NOTIFICATIONS, "Notifications",
            "tell you when a screenshot or recording is saved",
            "Settings → Notifications → HandyTuner",
            listOf(
                "Can I send you notifications? I'll use them for screenshots, recordings and quick buttons.",
                "Tap Allow. If no dialog appears, I'll take you to the notification settings instead.",
            ),
            "Thanks! I'll keep them short.", "Allow notifications",
        ),
    )

    fun byKind(kind: Kind) = all.first { it.kind == kind }

    /** Is it already granted? Same checks [Diagnostics.setup] has always used, in one place. */
    fun granted(ctx: Context, kind: Kind): Boolean = when (kind) {
        Kind.ACCESSIBILITY -> accessibilityGranted(ctx)
        Kind.USAGE -> usageGranted(ctx)
        Kind.NOTIFICATIONS -> Notifier.allowed(ctx)
    }

    /**
     * Is usage access on for us?
     *
     * `unsafeCheckOpNoThrow` answers ALLOWED here even when this package's own app-op mode is
     * `ignore` — the uid-level entry wins the lookup — while the OS enforces the package mode and
     * rejects our reads (`rejectTime` in `appops get`). Setup then said "usage access is already on"
     * while it was off, and the owner was never asked for it. Read the *raw* mode for our package and
     * only treat an explicit allow as granted; MODE_DEFAULT means nobody set anything, so the
     * platform's own answer decides.
     */
    fun usageGranted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val raw = ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        val checked = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return usageModeGrants(raw, checked)
    }

    /**
     * [raw] is the stored mode for this package, [checked] what the platform reports. Split out so the
     * decision is testable without a device: [BUGS.md #18].
     */
    fun usageModeGrants(raw: Int, checked: Int): Boolean = when (raw) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_DEFAULT -> checked == AppOpsManager.MODE_ALLOWED
        else -> false
    }

    /** Is our accessibility service actually switched on? */
    fun accessibilityGranted(ctx: Context) =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { serviceMatches(it, ctx.packageName, A11Y_SERVICE) }

    const val A11Y_SERVICE = ".OverlayService"

    /**
     * Whether one entry of `enabled_accessibility_services` names [cls] in [pkg].
     *
     * The system's format has two shapes and this app has shipped both: Android writes the flattened
     * component, which may be the short form (`com.electric.handytuner/.OverlayService`) or the full class
     * (`com.electric.handytuner/com.electric.handytuner.OverlayService`) depending on what the app's
     * manifest used. Comparing the raw string against one of them reported "off" while the service was on,
     * so setup never advanced past the accessibility step — the grant worked and the check said no.
     * Written out rather than using [android.content.ComponentName.unflattenFromString] so it can be
     * tested without a device.
     */
    fun serviceMatches(entry: String, pkg: String, cls: String): Boolean {
        val slash = entry.indexOf('/')
        if (slash <= 0) return false
        if (entry.take(slash) != pkg) return false
        val named = entry.substring(slash + 1)
        return named == cls || named == (if (cls.startsWith(".")) pkg + cls else cls)
    }

    const val ODIN_ASSISTANT = "com.odin.gameassistant"

    /**
     * [raw] `enabled_accessibility_services` minus Odin Assistant's entries, or null when it has none.
     *
     * Odin Assistant's accessibility service is its foreground-app watcher: it applies its own per-game
     * performance and fan mode, which fights the PULSE engine (odin-performance-system.md). The owner
     * asked for it off, and a factory reset or AYN update switches it back on, so the overlay re-checks
     * on every start. The app itself stays installed: the Controller page opens its gamepad test.
     */
    fun withoutOdinAssistant(raw: String): String? {
        val entries = raw.split(':').filter { it.isNotBlank() }
        val kept = entries.filterNot { it.substringBefore('/') == ODIN_ASSISTANT }
        return if (kept.size == entries.size) null else kept.joinToString(":")
    }

    /**
     * The page that grants [kind], or null when this Android build has none. Some of these only resolve
     * on newer versions, so each one is checked before being handed out: a button that opens nothing is
     * worse than no button.
     */
    fun intent(ctx: Context, kind: Kind): Intent? {
        val pm = ctx.packageManager
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        val tries = when (kind) {
            Kind.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            // From Android 11 this can open HandyTuner's own row instead of the list of every app that asks
            // for usage access. Settings.EXTRA_APPOP_STR is @hide in AOSP, hence the literal: if a future
            // Android stops honouring it the owner lands on the usage-access list instead, which is the same
            // page one tap further along, not a dead end.
            Kind.USAGE -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .putExtra("android:appopStr", AppOpsManager.OPSTR_GET_USAGE_STATS),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
            Kind.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                appInfo,
            )
        }
        return tries.firstOrNull { runCatching { pm.resolveActivity(it, 0) }.getOrNull() != null }
    }

    /**
     * The way out when [intent] is the wrong way round: from Android 13, a sideloaded build can't have
     * its accessibility service switched on from the Accessibility page at all — the switch is greyed
     * out until "Allow restricted settings" is turned on from the app's own info page. HandyTuner is
     * sideloaded (it is not in the Play Store), so this is the normal case on 13+, not an edge case.
     * Null on older versions, where the greyed-out switch doesn't exist.
     */
    fun escape(ctx: Context, kind: Kind): Intent? {
        if (kind != Kind.ACCESSIBILITY || Build.VERSION.SDK_INT < 33) return null
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    }

    /** What HandyHelper says to open the escape hatch (only shown when [intent] alone won't work). */
    val escapeSteps = listOf(
        "That switch is grayed out: Android hides it for apps installed outside the Play Store.",
        "I'll open HandyTuner's app info. Tap the ⋮ at the top right and turn on \"Allow restricted settings\".",
        "Then the switch in Accessibility will work.",
    )

    val escapeButton = "Open HandyTuner's app info"
}