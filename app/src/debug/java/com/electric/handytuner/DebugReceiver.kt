// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** adb shell am broadcast -n com.electric.handytuner/.DebugReceiver --es cmd "menu|hud|keyedit|pulse <state|tier N|fan N|autotdp on|off FPS|overlay on|off>" */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = OverlayService.instance ?: return
        when (intent.getStringExtra("cmd")) {
            "menu" -> svc.debugMenu()
            "hud" -> svc.toggleHud()
            "keyedit" -> svc.openKeyEditor()
            else -> intent.getStringExtra("cmd").orEmpty().split(" ").let { a ->
                // Benchmarks (docs/benchmarks.md): switch HandyTuner's own part on its own, e.g. "profile LOW_PING".
                if (a.firstOrNull() == "profile") svc.bgRun {
                    runCatching { svc.runner().apply(Profile.valueOf(a[1])) }
                    android.util.Log.i("HandyTuner", "debug profile -> ${svc.runner().active}")
                } else pulse(svc, a)
            }
        }
    }

    /** Drives the Pulse fork's remote control from adb; the answer goes to logcat (tag HandyTuner). */
    private fun pulse(svc: OverlayService, a: List<String>) {
        if (a.firstOrNull() != "pulse") return
        val r = when (a.getOrNull(1)) {
            "state" -> svc.pulse.state()?.toString()
            "tier" -> svc.pulse.call { it.setTier(a[2].toInt()) }
            "fan" -> svc.pulse.call { it.setFanMode(a[2].toInt()) }
            "autotdp" -> svc.pulse.call { it.setAutoTdp(a[2] == "on", a.getOrNull(3)?.toInt() ?: 60) }
            "overlay" -> svc.pulse.call { it.setOverlayEnabled(a[2] == "on") }
            "cap" -> svc.pulse.call { it.setFrameCap(a[2].toInt()) }
            else -> "?"
        }
        android.util.Log.i("HandyTuner", "debug pulse ${a.drop(1).joinToString(" ")} -> $r (connected=${svc.pulse.connected})")
    }
}
