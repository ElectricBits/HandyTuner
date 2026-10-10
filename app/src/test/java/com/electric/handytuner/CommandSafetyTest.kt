// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep a shell command from bricking the Odin or taking out HandyTuner's own way in.
 *
 * [PServer.run] is the only door, so [PServer.unsafeIn] is what every command is judged by; the
 * kill.sh script is judged here in text instead, because only `sh <path>` reaches PServer and its
 * body never does.
 */
class CommandSafetyTest {

    // --- never-touch nodes -------------------------------------------------

    @Test fun refusesEveryNeverTouchNode() {
        for (node in PServer.NEVER_TOUCH)
            assertNotNull("should refuse $node", PServer.unsafeIn("echo $node > /sys/devices/so/$node 1"))
    }

    @Test fun refusesANodeEvenInAPathOrARead() {
        // Substring matching, so a read can't be the way around it.
        assertNotNull(PServer.unsafeIn("cat /sys/devices/virtual/fake_soh"))
        assertNotNull(PServer.unsafeIn("settings put global ship_mode_en 1"))
        // wireless_fw covers every wireless_fw_* child.
        assertNotNull(PServer.unsafeIn("cat /sys/kernel/wireless_fw_update"))
    }

    @Test fun allowsEveryNodeHandyTunerActuallyUses() {
        // HandyTuner writes no sysfs nodes at all — only these. If a refusal ever landed on one of
        // these, a working feature would break silently, so they are pinned here.
        for (cmd in ALLOWED) assertNull("should allow: $cmd", PServer.unsafeIn(cmd))
    }

    // --- PServerBinder must survive ----------------------------------------

    @Test fun refusesAnythingThatKillsPServer() {
        assertNotNull(PServer.unsafeIn("killall pservice"))
        assertNotNull(PServer.unsafeIn("pkill pservice"))
        assertNotNull(PServer.unsafeIn("ps -A | grep pservice"))
        assertNotNull(PServer.unsafeIn("pkill -f pserver"))
        assertNotNull(PServer.unsafeIn("pkill -9 -f sh"))
    }

    @Test fun allowsTheSafeWayToStopARecording() {
        // The real command: exact match, no -f. This is the regression that made pkill -f unsafe,
        // so it is worth pinning that the working version still passes.
        assertNull(PServer.unsafeIn("pkill -INT -x screenrecord"))
    }

    // --- kill.sh ----------------------------------------------------------

    @Test fun killScriptNeverTouchesANeverTouchNodeOrPServer() {
        val sh = Actions.killScriptText("com.electric.handytuner")
        for (node in PServer.NEVER_TOUCH) assertTrue("kill.sh mentions $node", node !in sh)
        assertTrue("kill.sh mentions pservice", "pservice" !in sh)
        assertTrue("kill.sh uses pkill -f", "pkill" !in sh)
    }

    @Test fun exesScriptOnlyLists() {
        val sh = exesScriptText()
        for (node in PServer.NEVER_TOUCH) assertTrue("exes.sh mentions $node", node !in sh)
        assertTrue("exes.sh mentions pservice", "pservice" !in sh)
        // It exists to answer "what's running", so it must not be able to change anything.
        for (bad in listOf("kill", "rm ", "chmod", "pkill", "killall", ">" ))
            assertTrue("exes.sh can change things: $bad", bad !in sh)
    }

    @Test fun romsScriptOnlyReads() {
        val sh = romsScriptText()
        for (node in PServer.NEVER_TOUCH) assertTrue("roms.sh mentions $node", node !in sh)
        for (bad in listOf("kill", "rm ", "chmod", "pkill", "killall", ">", "settings put", "pm ", "am "))
            assertTrue("roms.sh can change things: $bad", bad !in sh)
        assertTrue("roms.sh reads the grants", sh.startsWith("dumpsys activity permissions"))
    }

    @Test fun killScriptKeepsTheGamePulseAndHandyTuner() {
        val sh = Actions.killScriptText("com.electric.handytuner")
        // The last game is "$1", so it can't be pinned by name; it is skipped three ways instead.
        assertTrue("doesn't spare the last game", sh.contains("\"\$1\""))
        assertTrue("doesn't spare Pulse", sh.contains(Actions.PULSE))
        assertTrue("doesn't spare itself", sh.contains("com.electric.handytuner"))
        assertTrue("kills system processes", sh.contains("*.*) ;; *) continue"))
    }

    // --- length -----------------------------------------------------------

    @Test fun everyCommandIsShortEnoughToRun() {
        // PServer drops 256+ characters silently, so a command that grew too long would just stop
        // working. The confirmation suffix is added on top, hence the smaller budget.
        for (cmd in ALLOWED) assertTrue("too long: $cmd", cmd.length + SUFFIX_LEN < 256)
    }

    private companion object {
        const val SUFFIX_LEN = 14   // " && echo __ht_ok"

        /** The commands HandyTuner really sends, as of this commit. */
        val ALLOWED = listOf(
            "settings get global wifi_scan_always_enabled",
            "settings put global wifi_scan_always_enabled 0",
            "settings delete global window_animation_scale",
            "pm disable-user --user 0 com.google.android.gms",
            "pm enable com.google.android.gms",
            "pm disable-user --user 0 com.odin.gameassistant",
            "pm enable com.odin.gameassistant",
            "am kill-all",
            "am stack remove",
            "pidof com.kei.pulse",
            "pkill -INT -x screenrecord",
            "sh /data/user/0/com.electric.handytuner/files/kill.sh com.example.game 1",
            "cat /sys/class/gpio5_pwm2/duty",
            "dumpsys battery",
            "screencap -p /data/local/tmp/shot.png",
            "cp /data/local/tmp/shot.png /sdcard/shot.png && echo __ht_ok",
            "settings put system screen_brightness 128",
            "cmd game mode performance",
            "service call activity 111 i32 42"
        )
    }
}