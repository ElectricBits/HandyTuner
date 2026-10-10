// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.kei.pulse.control.IPulseControl
import org.json.JSONObject

/**
 * HandyTuner's side of the Pulse fork's remote control (docs/architecture.md, "HandyTuner ↔ Pulse
 * fork link"). Stock Pulse has no such service: then [connected] stays false and the menu shows
 * read-only status. Binding also lends Pulse this process's priority, so the low-memory killer
 * should take it less often in games (to verify).
 *
 * Two things this class exists to get right:
 * - **Which version.** The interface only ever grows, so a fork older than a feature simply doesn't
 *   have that method. Calling it anyway fails inside [call] and looks exactly like the feature not
 *   working, so [version] is cached and callers gate on it (`Applied`).
 * - **Why it isn't working.** "Not installed" and "installed, but it's the wrong build" need
 *   completely different advice, so the failures are told apart in [problem] instead of collapsing
 *   to `null` (docs/BUGS.md #11, #12).
 */
class PulseLink(private val ctx: Context, private val publishes: Boolean = true) {
    @Volatile private var remote: IPulseControl? = null
    @Volatile private var remoteVersion = 0
    @Volatile private var bindDenied = false
    @Volatile private var lastError: Throwable? = null
    @Volatile private var noState = false

    val connected get() = remote != null

    /**
     * The link is up but the fork's `state()` came back empty: PULSE's foreground watcher isn't running, so
     * there is nothing in front to read. Its own rule ([WatcherActivation] in the fork) stops that watcher
     * when no per-app / AutoTDP / OSD / quick-access / managed-fan feature is on, and the setters need it
     * just as much as the read does — so this is "a setting is off in PULSE", not "the fork is missing".
     */
    val emptyState get() = noState

    /** The fork's `IPulseControl.version()`, or 0 while unknown. */
    val version get() = remoteVersion

    /** Whether the installed fork is new enough for [feature] (`Applied.Feature`). */
    fun supports(feature: Applied.Feature) = remoteVersion >= Applied.needsVersion(feature)

    /** Why the fork can't be used, in words for the user. null when it can. */
    fun problem(): String? = when {
        // PULSE is built in, so there is no "not installed" or "wrong build" any more: only not up yet, or asleep.
        !connected && lastError != null -> "the PULSE engine stopped answering"
        !connected -> "the PULSE engine hasn't started yet"
        noState -> "the PULSE engine's watcher is asleep — give HandyTuner Usage access so it can see your games"
        remoteVersion > 0 -> null
        else -> "the PULSE engine answered but not with a version we understand"
    }

    /** Runs on the main thread each time the link comes up (again after a Pulse restart). */
    var onConnected: () -> Unit = {}

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = IPulseControl.Stub.asInterface(binder)
            bindDenied = false
            // A fork older than HandyTuner still binds; only version() tells the two apart.
            remoteVersion = call { it.version() } ?: 0
            Log.i(TAG, "pulse link up, v$remoteVersion")
            publish()
            onConnected()
        }
        // Pulse died (e.g. the low-memory killer): Android re-binds by itself once it's back.
        override fun onServiceDisconnected(name: ComponentName) { remote = null; noState = false; Log.i(TAG, "pulse link down"); publish() }
        // Pulse was reinstalled: this binding is dead for good, so make a new one.
        override fun onBindingDied(name: ComponentName) { remote = null; noState = false; runCatching { ctx.unbindService(this) }; publish(); connect() }
    }

    fun connect(): Boolean {
        // The engine's watcher only starts itself at boot or after an update; start it here too so a
        // fresh install works without a reboot. It stops itself again if it has nothing to do.
        runCatching { com.kei.pulse.appwatch.ForegroundAppMonitorService.start(ctx) }.onFailure { Log.w(TAG, "pulse watcher start: $it") }
        val ok = try {
            ctx.bindService(Intent().setComponent(ComponentName(ctx.packageName, SERVICE)), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // The manifest permission is signature-level, so this is a mismatched build of Pulse.
            bindDenied = true; lastError = e; false
        }
        if (!ok) Log.i(TAG, "no pulse link")
        return ok
    }

    fun disconnect() { runCatching { ctx.unbindService(conn) }; remote = null; remoteVersion = 0; noState = false; publish() }

    /**
     * Leaves a note for the rest of the app ([PulseStatus]): this class lives in the `:overlay` process,
     * but Diagnostics needs to know whether the fork is there and whether its watcher is awake.
     *
     * Written whenever the answer changes, and refreshed once a minute otherwise so a quiet stretch doesn't
     * let the note go stale ([PulseStatus.STALE_MS] means "the overlay isn't running", not "nothing
     * changed"). Called from wherever this class already hears something, so no timer is needed.
     */
    private fun publish() {
        if (!publishes) return   // the app's own link (Tuning page): the status note is :overlay's to write
        val now = System.currentTimeMillis()
        val state = PulseStatus.State(connected, remoteVersion, noState, now)
        val prev = lastPublished
        if (prev != null && prev.linked == state.linked && prev.version == state.version &&
            prev.watcherAsleep == state.watcherAsleep && now - prev.at < REFRESH_MS) return
        lastPublished = state
        PulseStatus.write(ctx, state)
    }

    /** Called from the overlay's tick: the note has to be rewritten on a quiet stretch, or Diagnostics reads it as stale. */
    fun refresh() = publish()

    @Volatile private var lastPublished: PulseStatus.State? = null

    /** null when the fork isn't there or the call failed (it never throws into the caller). */
    fun <T> call(block: (IPulseControl) -> T): T? = remote?.let { r ->
        try {
            block(r).also { lastError = null }
        } catch (e: Throwable) {
            // Kept, not just logged: this is what lets problem() say "wrong build" rather than "gone".
            lastError = e
            Log.w(TAG, "pulse call: $e")
            null
        }
    }

    /**
     * null when the fork isn't there, the call failed, or PULSE has no watcher running (its `state()` is
     * then the empty string). [emptyState] tells those last two apart, so callers can say which.
     */
    fun state(): JSONObject? {
        val raw = call { it.state() }
        val parsed = raw?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Only a fork that answered counts: a failed call leaves the last known answer alone.
        if (raw != null) {
            noState = parsed == null
            if (noState) Log.i(TAG, "pulse state empty: its watcher isn't running")
            publish()
        }
        return parsed
    }

    /** Pulse's telemetry (CPU/GPU °C, loads). Its `cpuC` is what the HUD shows. */
    fun stats(): JSONObject? = call { it.stats() }?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** The Tuning page's device-wide engine settings (v5), or null. */
    fun engineSettings(): JSONObject? = call { it.engineSettings() }?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** [state] parsed into something comparable, with the version that produced it. */
    fun actual(): Applied.Actual? = Applied.read(state(), remoteVersion)

    companion object {
        const val SERVICE = "com.kei.pulse.control.ControlService"
        private const val TAG = "HandyTuner"

        /** How long an unchanged status note stays good for, in [PulseStatus] terms. */
        private const val REFRESH_MS = 60_000L
    }
}