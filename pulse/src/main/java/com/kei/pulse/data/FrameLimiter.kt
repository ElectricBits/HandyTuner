// Part of PULSE by keiretrogaming and contributors (GPL-2.0), built on ClusterTune and O2P Tweaks.
// Modified by ElectricBits for HandyTuner between 2026-09-28 and 2026-10-07; changes listed in pulse/NOTICE.md.
package com.kei.pulse.data

import com.kei.pulse.root.RootSupport

/**
 * A real frame‑rate cap for AutoTDP targets via **Android Game Mode interventions** (`cmd game`).
 *
 * Setting a custom game mode (mode 4) with an `--fps` override caps the app's frame rate **without
 * touching the panel refresh** — so AutoTDP can hold the panel at 120 Hz (latency) yet limit a light
 * game to e.g. 60 fps, which clock‑trimming alone can't do. Confirmed working on the Odin 3 (Android 15):
 * `cmd game set --mode 4 --fps 60 <pkg>` → "fps-override: 60". On some Android 13 firmwares the
 * intervention isn't honored — calls are harmless there (best‑effort; AutoTDP's clock loop still runs).
 *
 * Per‑app and persistent until cleared, so AutoTDP sets it on engage and clears it on release. All shell
 * goes through [RootSupport] (PServer root).
 */
object FrameLimiter {

    /**
     * Cap [pkg] to [fps] (a no‑op cap when [fps] ≤ 0 — clears instead). Returns the `cmd game` output
     * (e.g. "fps-override: 40") so callers can confirm the firmware honored the value vs floored it.
     */
    fun setCap(pkg: String, fps: Int): String? {
        if (pkg.isBlank()) return null
        if (fps <= 0) {
            clear(pkg)
            return null
        }
        val out = RootSupport.runRootCommand("cmd game set --mode 4 --fps $fps ${pkg.shellSafe()}")
        // HandyTuner: Android 13 refuses mode 4, which left AutoTDP's 30/40 targets uncapped on the Odin 2 Portal
        // (measured 2026-10-07: target 30 ran at 77 fps). Fall back to the battery-mode override that works there.
        if (out?.contains("Invalid game mode") == true) {
            setCapOverride(pkg, fps)
            return "fps-override (battery mode): $fps"
        }
        return out
    }

    /** Remove the cap (back to standard mode, dropping the fps override at runtime). */
    fun clear(pkg: String) {
        if (pkg.isBlank()) return
        RootSupport.runRootCommand("cmd game set --mode 1 ${pkg.shellSafe()}")
        clearOverride(pkg)   // HandyTuner: and the Android 13 fallback, if setCap used it
    }

    /**
     * Android 13 has no custom mode 4, so [setCap] is refused there ("Invalid game mode: 4"). This enables
     * battery mode for [pkg] with a device_config override and picks it with [fps]. Verified on the Odin 2
     * Portal: Minecraft 119.6 → 29.9 fps within seconds, no restart. Used by HandyTuner's frame cap
     * (control/ControlService); AutoTDP keeps its own path. The app must be marked as a game.
     */
    fun setCapOverride(pkg: String, fps: Int) {
        val p = pkg.shellSafe().ifEmpty { return }
        // The device_config value carries the fps (40 isn't in `cmd game set --fps`'s list, and works this way).
        RootSupport.runRootCommand("device_config put game_overlay $p mode=3,fps=$fps")
        RootSupport.runRootCommand("cmd game mode battery $p")
    }

    fun clearOverride(pkg: String) {
        val p = pkg.shellSafe().ifEmpty { return }
        RootSupport.runRootCommand("cmd game reset $p")
        RootSupport.runRootCommand("device_config delete game_overlay $p")
    }

    private fun String.shellSafe(): String = filter { it.isLetterOrDigit() || it == '.' || it == '_' }
}
