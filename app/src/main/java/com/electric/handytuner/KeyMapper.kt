// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Controller buttons → screen taps, for Android games that only take touch.
 * Each game (package) gets a scheme: button, spot (as a fraction of the screen,
 * so it survives a rotation), and tap or hold. The accessibility service keeps a
 * mapped button from the game and taps the spot instead, via dispatchGesture:
 * no root.
 *
 * Buttons only: on Android 13 an accessibility service gets key events, not the
 * sticks' motion events (those need Android 14's motion-event sources).
 */
object KeyMapper {
    /** TAP: one tap. HOLD: finger down while held. AUTO: auto-fire, repeated taps while held. */
    enum class Mode { TAP, HOLD, AUTO }

    /** How mapped taps behave and whether their spots show in game. Shared file, like the schemes. */
    data class Settings(val show: Boolean = false, val opacity: Int = 35, val tapMs: Int = 40, val autoRate: Int = 10)

    private fun settingsFile(ctx: Context) = File(ctx.filesDir, "keymap_settings.properties")
    fun settings(ctx: Context): Settings = runCatching {
        val p = java.util.Properties().apply { settingsFile(ctx).inputStream().use { load(it) } }
        val d = Settings()
        Settings(p.getProperty("show")?.toBoolean() ?: d.show, p.getProperty("opacity")?.toIntOrNull() ?: d.opacity,
            p.getProperty("tapMs")?.toIntOrNull() ?: d.tapMs, p.getProperty("autoRate")?.toIntOrNull() ?: d.autoRate)
    }.getOrDefault(Settings())
    fun saveSettings(ctx: Context, s: Settings) = settingsFile(ctx).storeAtomic(
        java.util.Properties().apply {
            setProperty("show", s.show.toString()); setProperty("opacity", s.opacity.toString())
            setProperty("tapMs", s.tapMs.toString()); setProperty("autoRate", s.autoRate.toString())
        })

    /** Export/import: the whole keymaps.json to and from Downloads, so layouts can be backed up or shared. */
    val EXPORT = File("/sdcard/Download/HandyTuner-keymaps.json")
    fun exportAll(ctx: Context): Boolean = runCatching {
        PServer.ok("cp ${file(ctx).absolutePath} ${EXPORT.path}")   // Downloads isn't the app's to write
    }.getOrDefault(false)
    fun importAll(ctx: Context): Int {
        val text = PServer.run("cat ${EXPORT.path}") ?: return -1
        val incoming = runCatching { JSONObject(text) }.getOrNull() ?: return -1
        val merged = JSONObject(runCatching { file(ctx).readText() }.getOrDefault("{}"))
        incoming.keys().forEach { merged.put(it, incoming.get(it)) }
        file(ctx).writeAtomic(merged.toString())
        return incoming.length()
    }
    data class Spot(val key: Int, val x: Float, val y: Float, val mode: Mode = Mode.TAP)

    private fun file(ctx: Context) = File(ctx.filesDir, "keymaps.json")

    fun all(ctx: Context): Map<String, List<Spot>> = runCatching {
        val o = JSONObject(file(ctx).readText())
        o.keys().asSequence().associateWith { pkg ->
            val a = o.getJSONArray(pkg)
            List(a.length()) { i ->
                val s = a.getJSONObject(i)
                Spot(s.getInt("key"), s.getDouble("x").toFloat(), s.getDouble("y").toFloat(),
                    runCatching { Mode.valueOf(s.getString("mode")) }.getOrDefault(Mode.TAP))
            }
        }
    }.getOrDefault(emptyMap())

    fun get(ctx: Context, pkg: String) = all(ctx)[pkg].orEmpty()

    @Synchronized fun set(ctx: Context, pkg: String, spots: List<Spot>) {
        val m = all(ctx).toMutableMap()
        if (spots.isEmpty()) m.remove(pkg) else m[pkg] = spots
        file(ctx).writeAtomic(JSONObject().apply {
            m.forEach { (p, list) -> put(p, JSONArray(list.map { s ->
                JSONObject().put("key", s.key).put("x", s.x.toDouble()).put("y", s.y.toDouble()).put("mode", s.mode.name)
            })) }
        }.toString())
    }

    /** Native Android games only: GameNative and emulators already speak controller. */
    fun canMap(pkg: String?) = pkg != null && pkg !in GameId.WINDOWS_HOSTS && ':' !in pkg

    fun label(key: Int) = KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_").removePrefix("BUTTON_").removePrefix("DPAD_")
}

/**
 * Plays a scheme in the accessibility service. [onKey] returns true when it took
 * the key (the game doesn't see it). A hold keeps its finger down by continuing the
 * stroke until the button comes up.
 */
class KeyMapPlayer(private val svc: AccessibilityService) {
    private var spots: List<KeyMapper.Spot> = emptyList()
    var settings = KeyMapper.Settings()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoKey = 0
    private var autoFire: Runnable? = null
    private var holding: GestureDescription.StrokeDescription? = null
    private var holdKey = 0

    fun use(list: List<KeyMapper.Spot>) { spots = list }

    fun onKey(e: KeyEvent): Boolean {
        val s = spots.firstOrNull { it.key == e.keyCode } ?: return false
        val m = svc.resources.displayMetrics
        val x = s.x * m.widthPixels; val y = s.y * m.heightPixels
        val path = Path().apply { moveTo(x, y) }
        when (s.mode) {
            KeyMapper.Mode.TAP -> if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) tap(path)
            KeyMapper.Mode.AUTO -> when {
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> {
                    autoKey = e.keyCode
                    val every = 1000L / settings.autoRate.coerceIn(2, 20)
                    autoFire = object : Runnable { override fun run() { tap(path); main.postDelayed(this, every) } }.also { main.post(it) }
                }
                e.action == KeyEvent.ACTION_UP && e.keyCode == autoKey -> { autoFire?.let { main.removeCallbacks(it) }; autoFire = null }
            }
            KeyMapper.Mode.HOLD -> when {
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 -> {
                    holdKey = e.keyCode
                    // willContinue: the finger stays down after this short stroke, until the continuation lifts it.
                    holding = GestureDescription.StrokeDescription(path, 0, 50, true).also { dispatch(it) }
                }
                e.action == KeyEvent.ACTION_UP && e.keyCode == holdKey -> {
                    holding?.continueStroke(path, 0, 30, false)?.let { dispatch(it) }
                    holding = null
                }
            }
        }
        return true
    }

    private fun tap(path: Path) =
        svc.dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, settings.tapMs.toLong().coerceIn(20, 300))).build(), null, null)

    private fun dispatch(s: GestureDescription.StrokeDescription) =
        svc.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
}
