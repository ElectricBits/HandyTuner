// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.json.JSONObject

/**
 * The one place that answers "did the fork actually do that?" — the rulebook is
 * `docs/STATE_MODEL.md`.
 *
 * HandyTuner used to ask the fork to change things and either take the returned boolean at face
 * value or ignore it. Both lose: a boolean can be true while the device does something else, and a
 * silent refusal leaves a game running unprofiled with nothing said. Every call site that changes
 * something the fork owns goes through here, so a new feature cannot invent a fourth way of
 * deciding whether a change worked.
 *
 * The comparison is deliberately pure (no Android, no Binder) so it can be unit-tested; [read] is
 * the only part that touches JSON.
 */
object Applied {

    /** Interface versions, from `IPulseControl.aidl`. New methods only ever get appended. */
    const val STATS_VERSION = 2
    const val CAPS_VERSION = 3
    const val DEFAULT_VERSION = 4
    const val ENGINE_VERSION = 5

    /** The fork's per-game binding names a tier like this (`state()` → `binding`). */
    private val TIER_KEYS = listOf("MAX", "BALANCED", "POWER_SAVING")

    /** `state()` calls the global tier by label, unlike the per-game binding, which uses TIER_KEYS. */
    private val TIER_LABELS = listOf("Max", "Balanced", "Power Saving", "Custom")

    /** Which fork version a feature needs, for the "too old" message and for greying a control. */
    enum class Feature { PULSE_STATS, FRAME_CAP, ALL_GAMES_DEFAULT, ENGINE_SETTINGS }

    fun needsVersion(feature: Feature): Int = when (feature) {
        Feature.PULSE_STATS -> STATS_VERSION
        Feature.FRAME_CAP -> CAPS_VERSION
        Feature.ALL_GAMES_DEFAULT -> DEFAULT_VERSION
        Feature.ENGINE_SETTINGS -> ENGINE_VERSION
    }

    /**
     * What the fork reports it has right now: a parsed `state()`, plus the version that produced it.
     *
     * A missing number becomes -1, not 0, because "this version doesn't offer it" and "it's zero"
     * are different facts: 0 is a real `frameCap` (no cap). Collapsing them is exactly how a
     * too-old fork would come to look like a working one.
     */
    data class Actual(
        val version: Int,
        val game: String?,
        val binding: String?,
        val fps: Int,
        val frameCap: Int,
        val fanMode: Int,
        val tierLabel: String?,
        val autoTdpDefault: Boolean,
    )

    /** `state()` is empty when the fork isn't running; that arrives here as null, not as zeroes. */
    fun read(json: JSONObject?, version: Int): Actual? {
        if (json == null) return null
        fun num(k: String) = if (json.has(k) && !json.isNull(k)) json.optInt(k) else -1
        fun str(k: String) = if (json.has(k) && !json.isNull(k)) json.optString(k).takeIf { it.isNotEmpty() } else null
        return Actual(
            version = version,
            game = str("game"),
            binding = str("binding"),
            fps = num("fps"),
            frameCap = num("frameCap"),
            fanMode = num("fanMode"),
            tierLabel = str("tier"),
            autoTdpDefault = if (json.has("autoTdpDefault")) json.optBoolean("autoTdpDefault") else false,
        )
    }

    /** One thing we wanted that the device isn't doing, and the reason we can honestly give. */
    data class Problem(val what: String, val wanted: String, val got: String, val why: String) {
        /** One line in plain words, for a notification or the HUD (Pulse's "tier:MAX" becomes "Max"). */
        val message get() = "Wanted $what, but PULSE is on ${plain(got)} (${plain(why)})"

        companion object {
            fun plain(s: String) = s.replace("auto:tdp", "AutoTDP").replace("auto:off", "stock")
                .replace(Regex("tier:([A-Z_]+)")) { m -> TIER_WORDS[m.groupValues[1]] ?: m.groupValues[1] }
                .replace("the fork", "PULSE")
            private val TIER_WORDS = mapOf("MAX" to "Max", "BALANCED" to "Balanced", "POWER_SAVING" to "Saver", "CUSTOM" to "Custom")
        }
    }

    /**
     * Compare what a game should have against what the fork says it has. Empty means everything
     * asked for is in place.
     *
     * [accepted] is what the setter returned. `true` with a mismatch is a different, more
     * interesting failure than a refusal, and is reported as one.
     */
    fun problems(want: PulsePart, got: Actual, accepted: Boolean = true): List<Problem> {
        val out = mutableListOf<Problem>()

        // AutoTDP and a tier are one choice, never both (IPulseControl.aidl).
        if (want.autoFps != null) {
            if (got.binding != "auto:tdp") out += Problem(
                "AutoTDP ${want.autoFps} fps", "auto:tdp", got.binding ?: "nothing",
                refused(got, accepted))
            else if (got.fps >= 0 && got.fps != want.autoFps) out += Problem(
                "AutoTDP ${want.autoFps} fps", "${want.autoFps} fps", "${got.fps} fps",
                "this chip's own list may not offer ${want.autoFps}")
        } else {
            val tier = want.tier ?: 0
            val key = TIER_KEYS.getOrNull(tier) ?: "?"
            if (got.binding != "tier:$key") out += Problem(
                "Tier ${TIER_LABELS.getOrNull(tier) ?: tier}", "tier:$key", got.binding ?: "nothing",
                refused(got, accepted))
        }

        // A cap of 0 means "remove any cap". On a fork too old to have caps there is nothing to
        // remove, so that is not a problem — but asking for a real cap there is, and says why.
        if (want.cap != got.frameCap && !(want.cap == 0 && got.frameCap < 0)) out += Problem(
            "Frame cap", if (want.cap == 0) "no cap" else "${want.cap} fps", capActual(got),
            capWhy(want, got, accepted))

        if (want.fan != got.fanMode) out += Problem(
            "Fan mode", Actions.FAN_NAMES[want.fan] ?: "${want.fan}",
            if (got.fanMode < 0) "not offered" else Actions.FAN_NAMES[got.fanMode] ?: "${got.fanMode}",
            refused(got, accepted))

        return out
    }

    private fun capActual(got: Actual) = when {
        got.frameCap < 0 -> "not offered"
        got.frameCap == 0 -> "no cap"
        else -> "${got.frameCap} fps"
    }

    /** The cap has its own reasons, and "fork too old" is the one most worth naming. */
    private fun capWhy(want: PulsePart, got: Actual, accepted: Boolean): String = when {
        got.version > 0 && got.version < CAPS_VERSION ->
            "the installed PULSE is v${got.version}, and caps need v$CAPS_VERSION"
        want.cap != 0 && got.binding == "auto:tdp" ->
            "AutoTDP is pacing the frames, so a cap would fight it"
        got.game == null -> "no game in front"
        accepted -> "the fork said yes but the cap isn't set"
        else -> "the fork refused, or nothing is in front"
    }

    private fun refused(got: Actual, accepted: Boolean) = when {
        got.game == null && got.binding == null -> "no game in front"
        accepted -> "the fork said yes but it didn't stick"
        else -> "the fork refused"
    }

    /**
     * The all-games default, which belongs to no single game, and can only partly be verified.
     *
     * `autoTdpDefault` is a real boolean (`autoTdpDefaultEnabled`), so it is checked. The default
     * *tier* deliberately is not: `state()` reports `s.activeTierLabel`, Pulse's active label, which
     * follows the last tier used anywhere rather than the default — `restorePulseDefault` already
     * says so and works around it. Comparing it would invent failures that aren't real, which is
     * worse than saying nothing.
     */
    fun defaultProblems(tier: Int, autoFdp: Int?, got: Actual): List<Problem> {
        val wanted = if (autoFdp != null) "AutoTDP $autoFdp fps" else TIER_LABELS.getOrNull(tier) ?: "$tier"
        if (got.version > 0 && got.version < DEFAULT_VERSION) return listOf(Problem(
            "Default for other apps", wanted, "not offered",
            "the installed PULSE is v${got.version}, and the all-games default needs v$DEFAULT_VERSION"))
        if (autoFdp != null && !got.autoTdpDefault) return listOf(Problem(
            "Default AutoTDP", "$autoFdp fps", "off", "the fork refused"))
        return emptyList()
    }
}