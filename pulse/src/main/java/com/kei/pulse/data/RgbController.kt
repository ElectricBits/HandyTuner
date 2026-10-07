// Part of PULSE by keiretrogaming and contributors (GPL-2.0), built on ClusterTune and O2P Tweaks.
// Modified by ElectricBits for HandyTuner between 2026-09-28 and 2026-10-06; changes listed in pulse/NOTICE.md.
package com.kei.pulse.data

import android.content.Context
import android.content.SharedPreferences
import com.kei.pulse.root.RootSupport

/**
 * RGB joystick-LED control for AYN / Retroid handhelds.
 *
 * The stock settings apps (`com.rp.settings` on the RP6, `com.odin.settings` on the Odin 3 — they share the
 * vendor `com.ro.*` codebase) expose the joystick LEDs through **`Settings.System` keys** that a framework
 * service applies to the LED hardware — the **same pattern as the fan** (we set the vendor's own value and its
 * service drives the hardware; we never poke raw sysfs). **Confirmed identical on both the Retroid Pocket 6 and
 * the AYN Odin 3** (`settings list system`):
 *
 *   joystick_led_light_picker_color = #AARRGGBB,#AARRGGBB   one ARGB color per stick (left,right)
 *   joystick_light_enabled          = 1,1                   on/off per stick
 *   led_light_brightness_percent    = 0..1                  master brightness (float; RP6 default 1.0, Odin 0.08)
 *
 * Writes go through PServer as root via `settings put`. The color value starts with `#`, which a shell treats as
 * a comment, so it MUST be single-quoted in the command. The AYN Thor (same vendor code) is expected to match but
 * is unverified.
 *
 * **Self-gated by [available]** (the color key existing), so it is automatically inert on any device that does
 * NOT expose the vendor joystick key — no per-SoC list needed; the gate opens itself wherever the key is present.
 *
 * **Switches the lights on (HandyTuner change, 2026-10-06):** upstream PULSE wrote only the color and left
 * `joystick_light_enabled` to AYN's tile; HandyTuner's owner wants picking a light mode to turn them on. The
 * original color, brightness and on/off switch are captured first, and [off] restores all three. (Use a
 * *solid* system effect, not an animated one, or the animation will override the color.)
 *
 * Phase 1: manual color + the two "info LED" mappings — [batteryColor] (Game-Boy-Color style green→red as it
 * drains) and [heatColor] (blue→orange→dark-red as it heats). Reactive-audio is a later phase.
 */
class RgbController(context: Context? = null) {

    /** AYN's settings app knows the joystick keys even before they exist (Odin 2 Portal, fresh after a reset). */
    private val aynDevice = context?.let { c -> runCatching { c.packageManager.getPackageInfo(AYN_SETTINGS, 0) }.isSuccess } ?: false

    // The user's TRUE pre-PULSE color + brightness, persisted so they survive a process death: captured the
    // first time PULSE writes over a NON-PULSE color, and [off] restores them even if THIS process never wrote
    // the LED (e.g. a crash mid-session). Null context (unit tests) ⇒ in-memory only, as before.
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var availableCached: Boolean? = null
    @Volatile private var captured = false
    @Volatile private var savedColor: String? = null
    @Volatile private var savedBrightness: String? = null
    @Volatile private var savedEnabled: String? = null
    @Volatile private var switchedOn = false
    @Volatile private var brightnessLevel: Float? = null
    @Volatile private var lastWritten: String? = null
    @Volatile private var lastWriteMs: Long = 0L
    @Volatile private var lastNoProbeMs: Long = 0L
    private fun now() = System.nanoTime() / 1_000_000

    /** True only on hardware that exposes the vendor joystick-LED key — false (and inert) elsewhere. */
    fun available(): Boolean {
        availableCached?.let { return it }
        if (lastNoProbeMs != 0L && now() - lastNoProbeMs < RECHECK_MS) return false
        // RootExec turns "null" (no such key) into null, the same as "PServer not ready", so the two can't be told
        // apart here. On an AYN device the lights exist even before the key does (Odin 2 Portal after a factory
        // reset: AYN writes it on first use), so that counts as yes. Elsewhere "no" is re-checked every 30 s.
        val v = RootSupport.runRootCommand("settings get system $KEY_COLOR")?.trim()
        val ok = !v.isNullOrEmpty() || aynDevice
        if (ok) availableCached = true else lastNoProbeMs = now()
        android.util.Log.d(TAG, "available=$ok (key=$v ayn=$aynDevice)")
        return ok
    }

    /** Remember the user's pre-PULSE color + brightness once, so [off] can restore them. */
    private fun captureOriginal() {
        if (captured) return
        captured = true
        val current = RootSupport.runRootCommand("settings get system $KEY_COLOR")?.trim()?.takeIf { it != "null" }
        // If the LED currently shows PULSE's OWN last-written color (we restarted/crashed while driving it),
        // keep the persisted true original instead of capturing PULSE's leftover as if it were the user's.
        if (prefs?.getBoolean(PREF_DONE, false) == true && current != null && current == prefs.getString(PREF_LAST, null)) {
            savedColor = prefs.getString(PREF_ORIG_COLOR, null)
            savedBrightness = prefs.getString(PREF_ORIG_BRIGHT, null)
            savedEnabled = prefs.getString(PREF_ORIG_ENABLED, null)
            return
        }
        // Genuine pre-PULSE state (first use, or the user changed it while PULSE was off) — THIS is the original.
        savedColor = current
        savedBrightness = RootSupport.runRootCommand("settings get system $KEY_BRIGHTNESS")?.trim()?.takeIf { it != "null" }
        savedEnabled = RootSupport.runRootCommand("settings get system $KEY_ENABLED")?.trim()?.takeIf { it != "null" }
        prefs?.edit()
            ?.putString(PREF_ORIG_ENABLED, savedEnabled)
            ?.putBoolean(PREF_DONE, true)
            ?.putString(PREF_ORIG_COLOR, savedColor)
            ?.putString(PREF_ORIG_BRIGHT, savedBrightness)
            ?.apply()
    }

    /**
     * Drive the (single, global) hardware brightness to [level] (0..1), writing only when it changes. Manual uses
     * 1.0 and bakes its real per-stick brightness into the color; the auto Battery/Heat modes use a dim
     * [AUTO_BRIGHTNESS] so the info-LED is a subtle glow rather than a beacon. [off] restores the captured original.
     */
    private fun applyBrightness(level: Float) {
        captureOriginal()
        // HandyTuner: picking a light mode switches the lights on (owner's call, 2026-10-06); off() puts the
        // switch back. Upstream PULSE only recolored and left the on/off toggle to AYN's tile.
        if (!switchedOn) { RootSupport.runRootCommand("settings put system $KEY_ENABLED 1,1"); switchedOn = true }
        if (brightnessLevel == level) return
        RootSupport.runRootCommand("settings put system $KEY_BRIGHTNESS $level")
        brightnessLevel = level
    }

    /** Set BOTH joystick LEDs to (r,g,b) 0..255 — used by the automatic Battery/Heat modes (dim info-LED). */
    fun setColor(r: Int, g: Int, b: Int) {
        if (!available()) return
        applyBrightness(AUTO_BRIGHTNESS)
        val hex = "#ff%02x%02x%02x".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        writeColorPair("$hex,$hex", "setColor")
    }

    /**
     * Manual full control: each stick its own color (ARGB at full value) and brightness (0..1, baked in here).
     * Brightness scales the RGB linearly — equivalent to HSV value, since the hardware brightness is set to max.
     */
    fun setManual(leftColor: Int, leftBrightness: Float, rightColor: Int, rightBrightness: Float) {
        if (!available()) return
        applyBrightness(1.0f)
        writeColorPair("${scaledHex(leftColor, leftBrightness)},${scaledHex(rightColor, rightBrightness)}", "setManual")
    }

    private fun writeColorPair(colorPair: String, tag: String) {
        val now = System.currentTimeMillis()
        // Re-assert an unchanged color on a slow cadence so we RECOVER if a stock RGB effect overran us (it
        // continuously rewrites the key), without flicker-fighting it every tick. A change always writes now.
        if (colorPair == lastWritten && now - lastWriteMs < REASSERT_MS) return
        // Single-quote the color: it starts with '#', which the shell would otherwise treat as a comment.
        RootSupport.runRootCommand("settings put system $KEY_COLOR '$colorPair'")
        lastWritten = colorPair
        lastWriteMs = now
        // Remember PULSE's own write so a later captureOriginal can tell its leftover from the user's color.
        prefs?.edit()?.putString(PREF_LAST, colorPair)?.apply()
        android.util.Log.d(TAG, "$tag $colorPair")
    }

    /** Hand the LEDs back: restore the user's captured color + brightness (and the on/off switch). */
    fun off() {
        if (!available()) return
        // If THIS process never captured (PULSE wrote the LED then died before [off]), pull the persisted true
        // original so we still hand the LEDs back instead of stranding them on PULSE's color.
        if (!captured) {
            if (prefs?.getBoolean(PREF_DONE, false) != true) return // never captured anywhere — nothing to restore
            captured = true
            savedColor = prefs.getString(PREF_ORIG_COLOR, null)
            savedBrightness = prefs.getString(PREF_ORIG_BRIGHT, null)
            savedEnabled = prefs.getString(PREF_ORIG_ENABLED, null)
        }
        val cmds = buildList {
            // No switch before HandyTuner touched them means they were never on: off.
            add("settings put system $KEY_ENABLED ${savedEnabled ?: "0,0"}")
            savedColor?.let { add("settings put system $KEY_COLOR '$it'") }
            savedBrightness?.let { add("settings put system $KEY_BRIGHTNESS '$it'") }
        }
        if (cmds.isNotEmpty()) RootSupport.runRootCommand(cmds.joinToString("; "))
        android.util.Log.d(TAG, "off → color=${savedColor ?: "—"} bright=${savedBrightness ?: "—"}")
        brightnessLevel = null
        lastWritten = null
        switchedOn = false
    }

    companion object {
        private const val RECHECK_MS = 30_000L
        private const val TAG = "PulseRgb"
        private const val KEY_COLOR = "joystick_led_light_picker_color"
        private const val KEY_BRIGHTNESS = "led_light_brightness_percent"
        private const val KEY_ENABLED = "joystick_light_enabled"
        private const val AYN_SETTINGS = "com.odin.settings"
        private const val PREF_ORIG_ENABLED = "orig_enabled"

        // Persisted original-color capture (survives process death — see [captureOriginal]/[off]).
        private const val PREFS_NAME = "pulse_rgb"
        private const val PREF_DONE = "orig_captured"
        private const val PREF_ORIG_COLOR = "orig_color"
        private const val PREF_ORIG_BRIGHT = "orig_brightness"
        private const val PREF_LAST = "pulse_last_color"

        /** Re-assert an unchanged color at least this often, to recover from the stock effect overriding us. */
        private const val REASSERT_MS = 8_000L

        /** Battery/Heat info-LED hardware brightness — a subtle glow, not a beacon. */
        private const val AUTO_BRIGHTNESS = 0.2f

        /** Scale an ARGB color's RGB channels by [brightness] (0..1) and format as the vendor `#ffRRGGBB`. */
        private fun scaledHex(color: Int, brightness: Float): String {
            val b = brightness.coerceIn(0f, 1f)
            val r = (((color shr 16) and 0xFF) * b).toInt().coerceIn(0, 255)
            val g = (((color shr 8) and 0xFF) * b).toInt().coerceIn(0, 255)
            val bl = ((color and 0xFF) * b).toInt().coerceIn(0, 255)
            return "#ff%02x%02x%02x".format(r, g, bl)
        }

        // Info-LED temperature stops (°C) — tuned for a handheld: cool idle ~35, warm ~55, hot ~75.
        private const val COOL_C = 35
        private const val WARM_C = 55
        private const val HOT_C = 75

        /** Battery → color: full green at 100 %, amber at 50 %, red as it nears empty (Game Boy Color vibe). */
        fun batteryColor(pct: Int): Triple<Int, Int, Int> {
            val green = Triple(0, 255, 0)
            val amber = Triple(255, 190, 0)
            val red = Triple(255, 0, 0)
            return when {
                pct >= 100 -> green
                pct >= 50 -> lerp(amber, green, (pct - 50) / 50f)
                pct >= 0 -> lerp(red, amber, pct / 50f)
                else -> red
            }
        }

        /** Temperature → color: blue when cool, through orange, to dark red when hot. */
        fun heatColor(tempC: Int): Triple<Int, Int, Int> {
            val blue = Triple(0, 80, 255)
            val orange = Triple(255, 140, 0)
            val darkRed = Triple(160, 0, 0)
            return when {
                tempC <= COOL_C -> blue
                tempC <= WARM_C -> lerp(blue, orange, (tempC - COOL_C).toFloat() / (WARM_C - COOL_C))
                tempC <= HOT_C -> lerp(orange, darkRed, (tempC - WARM_C).toFloat() / (HOT_C - WARM_C))
                else -> darkRed
            }
        }

        private fun lerp(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, t: Float): Triple<Int, Int, Int> {
            val tt = t.coerceIn(0f, 1f)
            fun c(x: Int, y: Int) = (x + (y - x) * tt).toInt().coerceIn(0, 255)
            return Triple(c(a.first, b.first), c(a.second, b.second), c(a.third, b.third))
        }
    }
}
