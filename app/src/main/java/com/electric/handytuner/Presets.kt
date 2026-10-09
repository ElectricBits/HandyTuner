// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * What a game gets from Pulse. Pulse runs either AutoTDP holding an FPS or a fixed tier (with an optional
 * frame cap) per game, never both, so exactly one of [autoFps] / [tier] is set (docs/decisions.md D16).
 */
data class PulsePart(val autoFps: Int?, val tier: Int?, val cap: Int, val fan: Int) {
    init { require((autoFps == null) != (tier == null)) { "AutoTDP or a tier, not both" } }

    /** Only values HandyTuner and the fork accept (caps: measured on the Portal; a 24 fps cap hung it). */
    val valid get() = (autoFps == null || autoFps in AUTO_FPS) && (tier == null || tier in 0..2) &&
        cap in CAPS && (cap == 0 || tier != null) && fan in FANS

    companion object {
        val AUTO_FPS = setOf(30, 40, 60, 90, 120)                 // the fork checks again against the chip's list
        val CAPS = setOf(0, 30, 40, 60)
        val FANS = setOf(1, 4, 5, 6)   // 6 = Hold temp (PULSE's own fan, target on the Tuning page)
    }
}

/** A preset: a built-in ([Preset]) or one the owner made ("custom:<name>"). */
data class PresetDef(val key: String, val label: String, val pulse: PulsePart, val profile: Profile) {
    val custom get() = key.startsWith(CUSTOM)
    companion object { const val CUSTOM = "custom:" }
}

/** A game's settings: the Pulse part (sent through the fork) and HandyTuner's own part ([Profile]). */
data class GameSettings(val preset: PresetDef, val pulse: PulsePart, val profile: Profile) {
    /** Changed from the Quick Menu since the preset was picked. */
    val tweaked get() = pulse != preset.pulse || profile != preset.profile
    val label get() = preset.label + if (tweaked) " (tweaked)" else ""

    /** "AutoTDP 60 fps · Smart fan · Battery Saver", for the Games page and notifications. */
    fun describe(): String = listOfNotNull(
        pulse.autoFps?.let { "AutoTDP $it fps" } ?: (TIERS[pulse.tier ?: 0] + if (pulse.cap > 0) " · cap ${pulse.cap}" else ""),
        Actions.FAN_NAMES[pulse.fan]?.let { "$it fan" },
        profile.takeIf { it != Profile.NORMAL }?.label,
    ).joinToString(" · ")

    companion object {
        fun of(p: PresetDef) = GameSettings(p, p.pulse, p.profile)
        fun of(p: Preset) = of(p.def)
        val TIERS = listOf("Max", "Balanced tier", "Saver")
    }
}

/** The built-in presets (owner-approved, D16). Tiers: 0 Max, 1 Balanced, 2 Saver. Fans: 1 Quiet, 4 Smart, 5 Sport, 6 Hold temp. */
enum class Preset(val label: String, val pulse: PulsePart, val profile: Profile) {
    BATTERY("Battery", PulsePart(null, 2, 30, 1), Profile.BATTERY_SAVER),
    // Shown as "Optimal" since 2026-10-06 (owner); the key stays BALANCED so saved games keep it.
    BALANCED("Optimal", PulsePart(60, null, 0, 4), Profile.NORMAL),
    PERFORMANCE("Performance", PulsePart(null, 0, 0, 5), Profile.NORMAL),
    // Renamed from "Low Latency" (2026-09-30): Low Latency mode is now its own network-only toggle.
    COMPETITIVE("Competitive", PulsePart(null, 0, 0, 5), Profile.LOW_PING);

    val def get() = PresetDef(name, label, pulse, profile)

    companion object { val NEW_GAME = BALANCED }
}

/**
 * Every game's settings, in games.json: HandyTuner is the source of truth (D15), and the :overlay process
 * re-sends a game's Pulse part whenever it comes to the front. Replaces profiles.properties, migrated once.
 */
object GameStore {
    fun file(ctx: Context) = File(ctx.filesDir, "games.json")

    fun all(ctx: Context): Map<String, GameSettings> {
        migrate(ctx)
        return parse(runCatching { file(ctx).readText() }.getOrDefault(""), CustomPresets.all(ctx))
    }

    fun get(ctx: Context, id: String) = all(ctx)[id]

    /** Locked: the app process and :overlay both write this, so the read-modify-write needs both together. */
    @Synchronized fun set(ctx: Context, id: String, s: GameSettings?) = FileGuard.locked(file(ctx)) {
        val m = all(ctx).toMutableMap()
        if (s == null) m.remove(id) else m[id] = s
        file(ctx).writeAtomic(render(m))
    }

    fun render(m: Map<String, GameSettings>): String = JSONObject().put("v", 1).put("games", JSONObject().apply {
        m.forEach { (id, s) ->
            put(id, JSONObject().put("preset", s.preset.key).put("autoFps", s.pulse.autoFps).put("tier", s.pulse.tier)
                .put("cap", s.pulse.cap).put("fan", s.pulse.fan).put("profile", s.profile.name))
        }
    }).toString(1)

    /** Anything unreadable or out of range is skipped, never guessed. A deleted custom preset keeps its name. */
    fun parse(text: String, customs: List<PresetDef>): Map<String, GameSettings> {
        val games = runCatching { JSONObject(text).getJSONObject("games") }.getOrNull() ?: return emptyMap()
        return games.keys().asSequence().mapNotNull { id ->
            runCatching {
                val o = games.getJSONObject(id)
                fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
                val pulse = PulsePart(int("autoFps"), int("tier"), int("cap") ?: 0, int("fan") ?: 4).also { require(it.valid) }
                val profile = Profile.valueOf(o.getString("profile"))
                val key = o.getString("preset").let { if (it == "LOW_LATENCY") "COMPETITIVE" else it }   // renamed 2026-09-30
                val def = Preset.entries.firstOrNull { it.name == key }?.def ?: customs.firstOrNull { it.key == key }
                    ?: PresetDef(key.also { require(it.startsWith(PresetDef.CUSTOM)) }, key.removePrefix(PresetDef.CUSTOM), pulse, profile)
                id to GameSettings(def, pulse, profile)
            }.getOrNull()
        }.toMap()
    }

    /**
     * The old per-game profiles become presets. Only Battery Saver maps across: old profiles never touched
     * Pulse, so Low Ping → Low Latency (Max tier, Sport fan) would have surprised; the owner chose Balanced.
     */
    fun fromOldProfile(p: Profile) = GameSettings.of(if (p == Profile.BATTERY_SAVER) Preset.BATTERY else Preset.BALANCED)

    @Synchronized private fun migrate(ctx: Context) = FileGuard.locked(file(ctx)) {
        val old = File(ctx.filesDir, "profiles.properties")
        if (file(ctx).exists() || !old.exists()) return@locked
        val p = Properties().apply { runCatching { old.inputStream().use { load(it) } } }
        val m = p.stringPropertyNames().mapNotNull { id ->
            runCatching { id to fromOldProfile(Profile.valueOf(p.getProperty(id))) }.getOrNull()
        }.toMap()
        file(ctx).writeAtomic(render(m))
        android.util.Log.i("HandyTuner", "migrated ${m.size} game profiles to games.json")
    }

    /** Game launchers and emulators that Android doesn't file under "Games". */
    val LAUNCHERS = setOf(
        "app.gamenative", "com.winlator", "com.retroarch", "com.retroarch.aarch64", "org.ppsspp.ppsspp",
        "org.dolphinemu.dolphinemu", "com.github.stenzek.duckstation", "xyz.aethersx2.android", "org.yuzu.yuzu_emu",
        "dev.eden.eden_emulator", "info.cemu.cemu", "org.citra.emu", "com.antutu.ABenchMark",
    )

    /** Emulators (not Windows hosts): their games are told apart by the file a frontend hands them (GameId.rom). */
    internal val EMULATORS get() = LAUNCHERS - GameId.WINDOWS_HOSTS - "com.antutu.ABenchMark"

    /** Counts as a game for sessions and presets. */
    fun isGame(ctx: Context, id: String): Boolean {
        if (':' in id) return true                               // a GameId: a Windows game in its host
        if (id in LAUNCHERS || get(ctx, id) != null) return true
        return runCatching { ctx.packageManager.getApplicationInfo(id, 0).category == ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)
    }
}

/**
 * The owner's own presets (presets.json), and sharing them as a file in Downloads (D14, F14). Imported files
 * are untrusted: size-capped, every value checked against the allowed sets, unknown fields ignored, names
 * cut short, and nothing in them is ever run.
 */
object CustomPresets {
    const val MAX_BYTES = 256 * 1024
    const val MAX_NAME = 40
    private const val EXPORT = "/sdcard/Download/HandyTuner-presets.json"

    fun file(ctx: Context) = File(ctx.filesDir, "presets.json")

    fun all(ctx: Context): List<PresetDef> = parseList(runCatching { file(ctx).readText() }.getOrDefault(""))

    @Synchronized fun save(ctx: Context, list: List<PresetDef>) = file(ctx).writeAtomic(render(list, emptyMap()))

    fun cleanName(raw: String) = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME)

    /** A name nobody has yet: built-ins are never overwritten, a clash gets " (2)", " (3)"… */
    fun freeName(name: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet() + Preset.entries.map { it.label.lowercase() }
        if (name.lowercase() !in used) return name
        return (2..999).map { "${name.take(MAX_NAME - 6)} ($it)" }.first { it.lowercase() !in used }
    }

    fun render(list: List<PresetDef>, games: Map<String, GameSettings>): String = JSONObject().put("v", 1)
        .put("presets", org.json.JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().put("name", d.label).put("autoFps", d.pulse.autoFps).put("tier", d.pulse.tier)
                    .put("cap", d.pulse.cap).put("fan", d.pulse.fan).put("profile", d.profile.name))
            }
        }).apply { if (games.isNotEmpty()) put("games", JSONObject(GameStore.render(games)).getJSONObject("games")) }
        .toString()

    fun parseList(text: String): List<PresetDef> {
        if (text.length > MAX_BYTES) return emptyList()
        val a = runCatching { JSONObject(text).getJSONArray("presets") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<PresetDef>()
        for (i in 0 until minOf(a.length(), 200)) runCatching {
            val o = a.getJSONObject(i)
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            val name = cleanName(o.getString("name")).also { require(it.isNotEmpty()) }
            val pulse = PulsePart(int("autoFps"), int("tier"), int("cap") ?: 0, int("fan") ?: 4).also { require(it.valid) }
            val unique = freeName(name, out.map { it.label })
            out += PresetDef(PresetDef.CUSTOM + unique, unique, pulse, Profile.valueOf(o.getString("profile")))
        }
        return out
    }

    /** What an import would add: presets under free names, and game settings only for games not set up yet. */
    data class Incoming(val presets: List<PresetDef>, val games: Map<String, GameSettings>, val skipped: Int)

    fun merge(text: String, mine: List<PresetDef>, myGames: Map<String, GameSettings>): Incoming? {
        if (text.length > MAX_BYTES) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val total = root.optJSONArray("presets")?.length() ?: 0
        val theirs = parseList(text)
        val added = mutableListOf<PresetDef>()
        theirs.forEach { d ->
            // Already have it (same name and same values, e.g. your own export): nothing to add.
            if (mine.any { it.label.equals(d.label, true) && it.pulse == d.pulse && it.profile == d.profile }) return@forEach
            val name = freeName(d.label, mine.map { it.label } + added.map { it.label })
            added += d.copy(key = PresetDef.CUSTOM + name, label = name)
        }
        val games = root.optJSONObject("games")?.let { g ->
            GameStore.parse(JSONObject().put("games", g).toString(), mine + added)
        }.orEmpty().filterKeys { it !in myGames && it.length <= 200 }
        return Incoming(added, games, total - theirs.size)
    }

    /** Downloads isn't the app's to write, so the system shell copies it there (as the key-map export does). */
    fun export(ctx: Context): Boolean = runCatching {
        val tmp = File(ctx.filesDir, "presets-export.json")
        tmp.writeAtomic(render(all(ctx), GameStore.all(ctx)))
        tmp.setReadable(true, false)
        PServer.ok("cp ${tmp.absolutePath} $EXPORT")
    }.getOrDefault(false)

    /** Copied in by the shell and read as a file: long shell replies come back garbled through PServer. */
    fun import(ctx: Context): Incoming? {
        val tmp = File(ctx.filesDir, "presets-import.json")
        if (!PServer.ok("cp $EXPORT ${tmp.absolutePath}")) return null
        PServer.run("chmod 644 ${tmp.absolutePath}")
        val text = runCatching { if (tmp.length() > MAX_BYTES) null else tmp.readText() }.getOrNull().also { tmp.delete() } ?: return null
        val inc = merge(text, all(ctx), GameStore.all(ctx)) ?: return null
        save(ctx, all(ctx) + inc.presets)
        inc.games.forEach { (id, s) -> GameStore.set(ctx, id, s) }
        return inc
    }
}
