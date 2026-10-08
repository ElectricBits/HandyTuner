// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import java.io.File
import java.util.Properties

/**
 * How the Odin is being played right now: in the hands, docked to a TV, with an external controller, or
 * both ("couch"). The :overlay watcher works this out every few seconds and switches presets, the HUD
 * size and the dock extras when it changes.
 */
enum class Setup(val label: String, val docked: Boolean, val pad: Boolean) {
    HANDHELD("Handheld", false, false), DOCKED("Docked", true, false), PAD("Controller", false, true), COUCH("Couch", true, true);

    companion object {
        fun of(docked: Boolean, pad: Boolean) = entries.first { it.docked == docked && it.pad == pad }
    }
}

/** Docked = a second display (USB-C video out to a TV or monitor), the system's own dock state, or, if asked, any charger. */
object Dock {
    /** [dockState] is Intent.EXTRA_DOCK_STATE (0 = undocked), null when the system never sent one. */
    fun decide(externalDisplays: Int, dockState: Int?, plugged: Boolean, chargerIsDock: Boolean) =
        externalDisplays > 0 || (dockState != null && dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) || (chargerIsDock && plugged)

    fun externalDisplays(ctx: Context) = ctx.getSystemService(DisplayManager::class.java)
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).count { it.displayId != Display.DEFAULT_DISPLAY }

    /** Sticky broadcasts, so null receivers: nothing is registered. */
    fun dockState(ctx: Context) = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_DOCK_EVENT))
        ?.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)

    fun plugged(ctx: Context) = (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    fun docked(ctx: Context, rules: SetupRules) =
        decide(externalDisplays(ctx), dockState(ctx), plugged(ctx), rules.chargerIsDock)
}

/** An external controller (Bluetooth, USB or a 2.4 GHz dongle). [battery] is a percent, null when the pad doesn't say. */
data class Pad(val id: Int, val name: String, val vendor: Int, val product: Int, val battery: Int?) {
    val brand get() = Pads.brand(vendor, name)
}

object Pads {
    const val VENDOR_8BITDO = 0x2dc8
    const val VENDOR_MICROSOFT = 0x045e
    const val VENDOR_SONY = 0x054c
    const val VENDOR_NINTENDO = 0x057e
    private val KNOWN = setOf(VENDOR_8BITDO, VENDOR_MICROSOFT, VENDOR_SONY, VENDOR_NINTENDO)

    /**
     * The Odin 2 Portal's own controls: AYN's driver names them "Xbox Wireless Controller" (2020:0112, with its own
     * Vendor_2020_Product_0112.kl) and Android marks them external, so the system's answer can't be trusted for them.
     */
    fun isOdinBuiltIn(vendor: Int, product: Int) = vendor == 0x2020 && product == 0x0112

    fun brand(vendor: Int, name: String) = when {
        vendor == VENDOR_8BITDO || name.contains("8bitdo", true) -> "8BitDo"
        vendor == VENDOR_MICROSOFT || name.contains("xbox", true) -> "Xbox"
        vendor == VENDOR_SONY -> "PlayStation"
        vendor == VENDOR_NINTENDO -> "Nintendo"
        else -> null
    }

    /**
     * Is this input device an external controller? [external] is the system's own answer (a hidden API,
     * null when it can't be read); without it, a pad that reports a battery or comes from a known
     * controller maker counts. [ignored] are names the owner marked as "not a controller", the safety
     * valve if the Odin's own controls are ever mistaken for one.
     */
    fun isExternalPad(sources: Int, virtual: Boolean, external: Boolean?, hasBattery: Boolean, vendor: Int, name: String,
                      ignored: Set<String>, product: Int = 0): Boolean {
        val pad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!pad || virtual || name in ignored || isOdinBuiltIn(vendor, product)) return false
        return external ?: (hasBattery || vendor in KNOWN)
    }

    private val isExternalMethod by lazy { runCatching { InputDevice::class.java.getMethod("isExternal") }.getOrNull() }
    private fun systemSaysExternal(d: InputDevice) = runCatching { isExternalMethod?.invoke(d) as? Boolean }.getOrNull()

    private fun batteryOf(d: InputDevice): Int? = runCatching {
        d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() && it >= 0f }?.let { Math.round(it * 100) }
    }.getOrNull()

    fun isExternal(d: InputDevice?, ignored: Set<String>) = d != null && isExternalPad(d.sources, d.isVirtual, systemSaysExternal(d),
        runCatching { d.batteryState.isPresent }.getOrDefault(false), d.vendorId, d.name, ignored, d.productId)

    /** Every external controller connected now, one entry per pad (a pad can show up as several input devices). */
    fun connected(ignored: Set<String>): List<Pad> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
        .filter { isExternal(it, ignored) }
        .distinctBy { it.descriptor }
        .map { Pad(it.id, it.name, it.vendorId, it.productId, batteryOf(it)) }

    /** Every gamepad Android sees, external or not, for the Controller page's list. */
    fun allGamepads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
        .filter { it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK }
        .filter { !it.isVirtual }.distinctBy { it.descriptor }

    /** A controller button or the D-pad, the only keys "ignore the Odin's own controls" ever holds back. */
    fun isPadKey(code: Int) = KeyEvent.isGamepadButton(code) || code in DPAD
    private val DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)

    /** What to check when buttons come out wrong. Worded as a hint: modes differ between pad models. */
    fun hint(brand: String?) = when (brand) {
        "8BitDo" -> "8BitDo pads send different buttons depending on their mode. Use the pad's Android or X-input mode; " +
            "if A and B (or X and Y) come out swapped, it's probably in Switch mode. Your pad's manual says which buttons pick the mode."
        "Nintendo" -> "Nintendo pads have A and B the other way round from Xbox ones."
        "PlayStation" -> "Cross is A and Circle is B."
        else -> null
    }

    /** Rumble for half a second, if the pad has a motor. Returns whether it could. */
    fun rumble(id: Int): Boolean = runCatching {
        val vm = InputDevice.getDevice(id)?.vibratorManager ?: return false
        if (vm.vibratorIds.isEmpty()) return false
        vm.vibrate(android.os.CombinedVibration.createParallel(android.os.VibrationEffect.createOneShot(500, 255)))
        true
    }.getOrDefault(false)
}

/**
 * What changes with the setup. A file like [HudStyle], since the app writes it and the :overlay process reads
 * it. Every preset is a preset key ([PresetDef.key]); null means "the game's own preset".
 */
data class SetupRules(
    val dockedPreset: String? = null,
    val padPreset: String? = null,
    /** Docked and a controller together. Null falls back to the docked preset, then the controller one. */
    val couchPreset: String? = null,
    /** Count any charger as docked, for docks without a TV. */
    val chargerIsDock: Boolean = false,
    /** Bigger HUD while docked, readable from the sofa. */
    val tvHud: Boolean = true,
    /** Dim the Odin's own screen while docked (the TV keeps its picture). */
    val dimScreen: Boolean = false,
    /** Stop the screen timing out while docked. */
    val keepAwake: Boolean = false,
    /** Pause PULSE's sleep underclock while docked; it comes back when undocked. */
    val sleepOff: Boolean = true,
    /** Hold back the Odin's own buttons while an external controller is connected. */
    val ignoreBuiltIn: Boolean = false,
    /** Hide the key-mapping markers while an external controller is connected. */
    val hideMarkers: Boolean = false,
    /** Notify when a controller disconnects mid-game or its battery runs low. */
    val padAlerts: Boolean = true,
    /** An app to open when the Odin is docked. */
    val launchOnDock: String? = null,
    /** Input device names the owner marked "not a controller". */
    val ignoredPads: Set<String> = emptySet(),
) {
    fun presetFor(setup: Setup): String? = when (setup) {
        Setup.HANDHELD -> null
        Setup.DOCKED -> dockedPreset
        Setup.PAD -> padPreset
        Setup.COUCH -> couchPreset ?: dockedPreset ?: padPreset
    }

    /** The settings a game really gets in [setup]: its own, or the setup's preset over them. */
    fun effective(g: GameSettings, setup: Setup, presets: List<PresetDef>): GameSettings {
        val key = presetFor(setup) ?: return g
        return presets.firstOrNull { it.key == key }?.let { GameSettings.of(it) } ?: g
    }

    fun toProperties() = Properties().apply {
        fun put(k: String, v: Any?) { if (v != null) setProperty(k, v.toString()) }
        put("dockedPreset", dockedPreset); put("padPreset", padPreset); put("couchPreset", couchPreset)
        put("chargerIsDock", chargerIsDock); put("tvHud", tvHud); put("dimScreen", dimScreen); put("keepAwake", keepAwake)
        put("sleepOff", sleepOff); put("ignoreBuiltIn", ignoreBuiltIn); put("hideMarkers", hideMarkers); put("padAlerts", padAlerts)
        put("launchOnDock", launchOnDock)
        if (ignoredPads.isNotEmpty()) setProperty("ignoredPads", ignoredPads.joinToString("\n"))
    }

    companion object {
        fun file(ctx: Context) = File(ctx.filesDir, "setup.properties")

        fun from(p: Properties): SetupRules {
            val d = SetupRules()
            fun str(k: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
            fun flag(k: String, def: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: def
            return SetupRules(
                dockedPreset = str("dockedPreset"), padPreset = str("padPreset"), couchPreset = str("couchPreset"),
                chargerIsDock = flag("chargerIsDock", d.chargerIsDock), tvHud = flag("tvHud", d.tvHud),
                dimScreen = flag("dimScreen", d.dimScreen), keepAwake = flag("keepAwake", d.keepAwake),
                sleepOff = flag("sleepOff", d.sleepOff), ignoreBuiltIn = flag("ignoreBuiltIn", d.ignoreBuiltIn),
                hideMarkers = flag("hideMarkers", d.hideMarkers), padAlerts = flag("padAlerts", d.padAlerts),
                launchOnDock = str("launchOnDock")?.takeIf { it.matches(Regex("[A-Za-z0-9._]+")) },
                ignoredPads = p.getProperty("ignoredPads")?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            )
        }

        fun load(ctx: Context): SetupRules = runCatching {
            from(Properties().apply { file(ctx).inputStream().use { load(it) } })
        }.getOrDefault(SetupRules())

        fun save(ctx: Context, r: SetupRules) = file(ctx).storeAtomic(r.toProperties())

        /** Every preset a setup can pick: the built-ins, then the owner's own. */
        fun presets(ctx: Context) = Preset.entries.map { it.def } + CustomPresets.all(ctx)
    }
}

/** Things a controller button (an 8BitDo's back paddles, say) can do instead of reaching the game. */
enum class PadAction(val label: String) {
    QUICK_MENU("Open the Quick Menu"), HUD("Show or hide the HUD"), SCREENSHOT("Screenshot"), RECORD("Start or stop recording"),
    NEXT_PRESET("Next preset for this game"), SPEED_UP("Speed Up"), AFK("AFK mode");

    companion object {
        fun file(ctx: Context) = File(ctx.filesDir, "pad_actions.properties")

        /** keycode → action. Anything unreadable is skipped. */
        fun parse(p: Properties): Map<Int, PadAction> = p.stringPropertyNames().mapNotNull { k ->
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val a = runCatching { valueOf(p.getProperty(k)) }.getOrNull() ?: return@mapNotNull null
            code to a
        }.toMap()

        fun load(ctx: Context): Map<Int, PadAction> = runCatching {
            parse(Properties().apply { file(ctx).inputStream().use { load(it) } })
        }.getOrDefault(emptyMap())

        fun save(ctx: Context, m: Map<Int, PadAction>) =
            file(ctx).storeAtomic(Properties().apply { m.forEach { (k, v) -> setProperty(k.toString(), v.name) } })
    }
}
