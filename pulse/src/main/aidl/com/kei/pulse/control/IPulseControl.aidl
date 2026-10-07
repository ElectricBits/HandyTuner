/*
 * HandyTuner — Copyright (C) 2026 ElectricBits
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.kei.pulse.control;

/**
 * How HandyTuner controls PULSE. Only apps holding com.kei.pulse.permission.CONTROL (signature)
 * may bind, and the service also checks the caller is HandyTuner. Every value is checked
 * against a fixed set; setters return false for anything else or when PULSE isn't running.
 */
interface IPulseControl {
    /** Interface version: 1 = up to setOverlayEnabled, 2 = adds stats(), 3 = frame caps, 4 = all-games default, 5 = engine settings. New methods only go at the end. */
    int version();
    /**
     * Live state as JSON, empty when PULSE isn't running: game, binding (the game's per-app binding:
     * "tier:MAX"… / "auto:tdp" / "auto:off" / null), tier (global), autoTdp, fps, fanMode, overlay,
     * autoTdpDefault and defaultFps (the all-games default; tier is its tier label), frameCap (v3: this game's cap, 0 = none), fpsOptions (the AutoTDP targets this chip offers).
     * Setters for tier and AutoTDP always change the game in front, never the all-games default. A tier binding and AutoTDP are one choice:
     * setTier replaces AutoTDP for that game, and setAutoTdp(true) replaces the tier.
     */
    String state();
    /** 0 Max, 1 Balanced, 2 Power Saving, 3 Custom — for the game in front. */
    boolean setTier(int tier);
    /** AutoTDP on/off and its target, from PULSE's own list for this chip. */
    boolean setAutoTdp(boolean on, int fps);
    /** 1 Quiet, 4 Smart, 5 Sport, 6 Custom. */
    boolean setFanMode(int mode);
    /** PULSE's own HUD on/off (HandyTuner shows the stats instead). */
    boolean setOverlayEnabled(boolean enabled);
    /**
     * v2. Live stats as JSON for HandyTuner's HUD (the last second's telemetry, cheap to ask every
     * second): cpuC, gpuC, cpuLoad, gpuLoad, gpuMhz, watts (battery draw), charging, mode (PULSE's
     * profile label). Empty when PULSE isn't running.
     */
    String stats();
    /**
     * v3. Cap the game in front at [fps] (30/40/60, the values measured on the Portal; 0 = remove) with Android 13's Game Mode
     * override. Refused while AutoTDP runs for that game (it paces frames itself; turning AutoTDP on
     * removes the cap). The cap stays with the game until removed.
     */
    boolean setFrameCap(int fps);
    /** v3. Remove every cap HandyTuner set (Reset to stock). */
    boolean clearFrameCaps();
    /** v4. The all-games default tier (what apps without their own setting get): 0 Max … 3 Custom. Turns the AutoTDP default off. */
    boolean setDefaultTier(int tier);
    /** v4. The all-games AutoTDP default and its target (to put a default back, e.g. Reset). */
    boolean setDefaultAutoTdp(boolean on, int fps);
    /**
     * v5. HandyTuner's Tuning page: device-wide engine settings as JSON — fanTargetC, autoTdpBias, sleep,
     * rgbMode, rgbColor ("RRGGBB"), rgbBrightness (0..100).
     */
    String engineSettings();
    /** v5. One key from engineSettings() and its new value, checked by EngineSettings.parse; false if refused. */
    boolean setEngineSetting(String key, String value);
}
