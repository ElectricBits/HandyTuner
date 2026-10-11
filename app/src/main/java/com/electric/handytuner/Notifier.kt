// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * HandyTuner's notifications, like Odin Assistant's: a quiet always-there one
 * with quick buttons, and short ones for things that happened (screenshot
 * saved, recording, profile switched, memory freed). Needs POST_NOTIFICATIONS,
 * asked for in the onboarding and fixable from Diagnostics.
 */
object Notifier {
    // "status2": a channel's importance can't change once created, and MIN hid it in the collapsed silent row.
    private const val STATUS = "status2"
    private const val EVENTS = "events"
    private const val ID_STATUS = 1; private const val ID_SHOT = 2; private const val ID_REC = 3
    private const val ID_PROFILE = 4; private const val ID_SPEED = 5
    private const val ID_APPLIED = 6
    private const val ID_SETUP = 7; private const val ID_PAD = 8

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(STATUS, "Status and quick buttons", NotificationManager.IMPORTANCE_LOW))
        deleteNotificationChannel("status")
        createNotificationChannel(NotificationChannel(EVENTS, "Screenshots, recordings and profiles", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { setSound(null, null) })
    }

    // Own group per channel: with 3+ ungrouped notifications Android bundles them all into one
    // collapsed "ranker_group", which buried the status one (seen in dumpsys on the Odin).
    private fun base(ctx: Context, channel: String) = Notification.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_sports_esports).setColor(0xFF0389FB.toInt()).setGroup(channel)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, cmd: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
        Intent(ctx, NotifyReceiver::class.java).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE)

    private fun view(ctx: Context, uri: Uri, type: String) = PendingIntent.getActivity(ctx, uri.hashCode(),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE)

    /** The always-there one: the hotkeys, and HUD / Quick Menu / Speed Up buttons. */
    fun status(ctx: Context, hudOn: Boolean) = nm(ctx).notify(ID_STATUS, base(ctx, STATUS)
        .setContentTitle("HandyTuner").setContentText("HUD ${if (hudOn) "on" else "off"} · Quick Menu: both sticks + R1")
        .setOngoing(true).setShowWhen(false).setContentIntent(openApp(ctx))
        .addAction(Notification.Action.Builder(null, if (hudOn) "Hide HUD" else "Show HUD", action(ctx, "hud", 1)).build())
        .addAction(Notification.Action.Builder(null, "Quick Menu", action(ctx, "menu", 2)).build())
        .addAction(Notification.Action.Builder(null, "Speed Up", action(ctx, "speedup", 3)).build())
        .build())

    fun screenshot(ctx: Context, uri: Uri?, path: String) {
        val b = base(ctx, EVENTS).setContentTitle("Screenshot saved").setContentText("Pictures › HandyTuner").setAutoCancel(true)
        runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()?.let {
            b.setLargeIcon(it).setStyle(Notification.BigPictureStyle().bigPicture(it))
        }
        uri?.let { b.setContentIntent(view(ctx, it, "image/png")) }
        nm(ctx).notify(ID_SHOT, b.build())
    }

    fun recording(ctx: Context) = nm(ctx).notify(ID_REC, base(ctx, EVENTS)
        .setContentTitle("Recording the screen…").setContentText("Up to 3 minutes").setOngoing(true)
        .setUsesChronometer(true).setWhen(System.currentTimeMillis())
        .addAction(Notification.Action.Builder(null, "Stop", action(ctx, "stoprec", 4)).build())
        .build())

    fun recordingSaved(ctx: Context, uri: Uri?) = nm(ctx).notify(ID_REC, base(ctx, EVENTS)
        .setContentTitle("Recording saved").setContentText("Movies › HandyTuner").setAutoCancel(true)
        .apply { uri?.let { setContentIntent(view(ctx, it, "video/mp4")) } }
        .build())

    fun preset(ctx: Context, s: GameSettings, game: String) = nm(ctx).notify(ID_PROFILE, base(ctx, EVENTS)
        .setContentTitle("${s.label} for $game").setContentText(s.describe()).setTimeoutAfter(8_000)
        .setAutoCancel(true).build())

    fun speedUp(ctx: Context, mb: Int) = nm(ctx).notify(ID_SPEED, base(ctx, EVENTS)
        .setContentTitle("Speed Up: freed $mb MB").setContentText("Background apps closed").setTimeoutAfter(6_000)
        .setAutoCancel(true).build())

    /**
     * A "Speed up" that closed nothing on purpose, because `last_game` couldn't be read and the last
     * game must not be closed. [ID_SPEED] again, so it replaces the usual result rather than arriving
     * as a second, contradictory notification.
     */
    fun nothingClosed(ctx: Context) = nm(ctx).notify(ID_SPEED, base(ctx, EVENTS)
        .setContentTitle("Speed Up: nothing closed").setContentText("Couldn't read last_game, so nothing was closed")
        .setTimeoutAfter(6_000).setAutoCancel(true).build())

    /**
     * A profile the fork didn't take. The watcher re-sends every few seconds, so the caller passes
     * this only when the message changes — otherwise a lasting problem would post one notification
     * every three seconds. [id] is deliberately the same for every case: this is "the profile isn't
     * being applied", one thing at a time, and a second one should replace it.
     */
    fun applyProblem(ctx: Context, what: String, why: String) = nm(ctx).notify(ID_APPLIED, base(ctx, EVENTS)
        .setContentTitle("Couldn't apply $what").setContentText(why).setTimeoutAfter(10_000)
        .setAutoCancel(true).setContentIntent(openApp(ctx)).build())

    /**
     * Safe mode: the overlay restarted too often, so it has stopped changing anything until the owner
     * clears it. Says where to clear it, because a notification that only reports a problem leaves the
     * device looking fine and staying stuck.
     */
    fun safeMode(ctx: Context) = nm(ctx).notify(ID_APPLIED, base(ctx, EVENTS)
        .setContentTitle("HandyTuner: safe mode")
        .setContentText("It restarted too often, so it has stopped changing anything. Clear this in the app → Diagnostics.")
        .setTimeoutAfter(20_000).setAutoCancel(true).setContentIntent(openApp(ctx)).build())

    /** The setup changed (docked, controller connected…) and what it switched to. */
    fun setup(ctx: Context, title: String, text: String) = nm(ctx).notify(ID_SETUP, base(ctx, EVENTS)
        .setContentTitle(title).setContentText(text).setTimeoutAfter(8_000).setAutoCancel(true).build())

    /** A controller dropped mid-game, or its battery is low. One at a time: a newer one replaces it. */
    fun pad(ctx: Context, title: String, text: String) = nm(ctx).notify(ID_PAD, base(ctx, EVENTS)
        .setContentTitle(title).setContentText(text).setTimeoutAfter(15_000).setAutoCancel(true).build())

    fun allowed(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

/** The notification buttons, handled in the :overlay process where the HUD and menu live. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = OverlayService.instance ?: return
        when (intent.getStringExtra("cmd")) {
            "hud" -> svc.toggleHud()
            "menu" -> svc.debugMenu()
            "speedup" -> svc.speedUpFromNotification()
            "stoprec" -> svc.stopRecordingFromNotification()
        }
    }
}
