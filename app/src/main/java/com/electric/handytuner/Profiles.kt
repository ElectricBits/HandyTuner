// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.Properties

/**
 * HandyTuner's own part of a game's settings (the Pulse part is in [PulsePart]; both are picked through a
 * [Preset]). Performance and fans are Pulse's, and Bluetooth is never touched (a BT controller or
 * headphones may be in use).
 */
enum class Profile(val label: String, val what: String) {
    NORMAL("Normal", "Changes nothing"),
    BATTERY_SAVER("Battery Saver", "Screen ≤35%, 60 Hz without PULSE, no background scans, background apps cleared"),
    LOW_PING("Low Latency", "Wi-Fi low-latency mode, no background scans"),
}

/**
 * Applies profiles (lives in the :overlay process, next to the Quick Menu). A
 * profile's changes are all recorded in [Originals] first, so [clear] puts
 * exactly those settings back.
 */
class ProfileRunner(private val ctx: Context, private val actions: Actions) {
    @Volatile var active = Profile.NORMAL; private set

    fun apply(p: Profile) {
        if (p == active) return
        clear()
        // Installed, not running: Pulse is often mid-restart after a low-memory kill, and pins the panel
        // refresh again when it's back, so a 60 Hz write would only start a fight.
        val pulse = actions.pulseInstalled()
        when (p) {
            Profile.NORMAL -> {}
            Profile.BATTERY_SAVER -> {
                actions.setBrightnessPct(minOf(actions.brightnessPct(), 35))
                if (!pulse) actions.setRefreshHz(60)
                scansOff(); actions.closeBackground()
            }
            // Network only, by the owner's rule: closing apps is Speed Up's job, and Pulse is never touched.
            Profile.LOW_PING -> { actions.lowPing(true); scansOff() }
        }
        active = p
    }

    /** After "Reset everything to stock" put all settings back from the app: only drop what this process holds. */
    fun forget() { actions.lowPing(false); active = Profile.NORMAL }

    fun clear() {
        if (active == Profile.NORMAL) return
        actions.lowPing(false)
        Originals.restore(ctx, KEYS)
        active = Profile.NORMAL
    }

    /** Background Wi-Fi and Bluetooth scanning for location: a battery cost, and scans cause ping spikes. */
    private fun scansOff() {
        Originals.put(ctx, "global:wifi_scan_always_enabled", "0")
        Originals.put(ctx, "global:ble_scan_always_enabled", "0")
    }

    companion object {
        /** Everything any profile writes. */
        val KEYS = listOf(
            "screen_brightness_mode", "screen_brightness", "peak_refresh_rate", "min_refresh_rate",
            "global:wifi_scan_always_enabled", "global:ble_scan_always_enabled",
        )
    }
}
