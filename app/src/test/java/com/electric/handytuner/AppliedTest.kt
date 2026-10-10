// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared "did it apply?" check (docs/STATE_MODEL.md). These are the cases that used to be
 * invisible: a silent refusal, a fork too old for the feature, and a setter that said yes while
 * the device did something else.
 */
class AppliedTest {

    private fun actual(
        version: Int = 4,
        game: String? = "com.mojang.minecraftpe",
        binding: String? = "tier:MAX",
        fps: Int = 60,
        frameCap: Int = 0,
        fanMode: Int = 5,
        autoTdpDefault: Boolean = false,
    ) = Applied.Actual(version, game, binding, fps, frameCap, fanMode, "Max", autoTdpDefault)

    /** Tier 0 Max, Sport fan, no cap — what the Performance preset asks for. */
    private val maxSport = PulsePart(null, 0, 0, 5)

    @Test fun `everything we asked for is already true`() {
        assertEquals(emptyList<Applied.Problem>(), Applied.problems(maxSport, actual()))
    }

    @Test fun `a tier the fork refused is reported with what it has instead`() {
        val p = Applied.problems(maxSport, actual(binding = "auto:tdp"), accepted = false)
        assertEquals(1, p.size)
        assertTrue(p.single().message, p.single().message.contains("PULSE is on AutoTDP"))
        assertTrue(p.single().message, !p.single().message.contains("tier:"))
    }

    @Test fun `AutoTDP is satisfied only when the target fps matches too`() {
        val want = PulsePart(60, null, 0, 5)
        assertEquals(emptyList<Applied.Problem>(), Applied.problems(want, actual(binding = "auto:tdp", fps = 60)))
        val p = Applied.problems(want, actual(binding = "auto:tdp", fps = 90))
        assertEquals(1, p.size)
        assertTrue(p.single().message, p.single().message.contains("chip's own list"))
    }

    /** The headline bug: a cap on a fork that predates caps must say "too old", not just "wrong". */
    @Test fun `a cap against a too-old fork names the version`() {
        val p = Applied.problems(PulsePart(null, 0, 40, 5), actual(version = 2, frameCap = -1))
        assertEquals(1, p.size)
        assertTrue(p.single().message, p.single().message.contains("v2"))
        assertTrue(p.single().message, p.single().message.contains("need v${Applied.CAPS_VERSION}"))
    }

    /** Asking for no cap on a fork with no caps is not a problem: there is nothing to remove. */
    @Test fun `removing a cap is not a problem on a fork that never had caps`() {
        val p = Applied.problems(PulsePart(null, 0, 0, 5), actual(version = 2, frameCap = -1))
        assertEquals(emptyList<Applied.Problem>(), p)
    }

/** AutoTDP paces frames itself, so the fork refuses a cap for that game. That has its own message. */
@Test fun `a cap while AutoTDP owns the frame rate explains itself`() {
        val p = Applied.problems(PulsePart(null, 0, 30, 5), actual(binding = "auto:tdp"), accepted = false)
        val cap = p.single { it.what == "Frame cap" }
        assertTrue(cap.message, cap.message.contains("AutoTDP is pacing"))
    }

    /** The subtlest failure: the setter returned true, and the cap still isn't there. */
    @Test fun `a setter that lied is reported differently from one that refused`() {
        val p = Applied.problems(PulsePart(null, 0, 30, 5), actual(binding = "tier:MAX", frameCap = 0), accepted = true)
        assertEquals(1, p.size)
        assertTrue(p.single().message, p.single().message.contains("said yes but"))
    }

    @Test fun `nothing in front is its own reason`() {
        val p = Applied.problems(maxSport, actual(game = null, binding = null), accepted = false)
        assertTrue(p.isNotEmpty())
        assertTrue(p.all { it.message.contains("no game in front") })
    }

    @Test fun `a fan the fork did not take is reported`() {
        val p = Applied.problems(PulsePart(null, 0, 0, 1), actual(fanMode = 5), accepted = false)
        assertEquals(1, p.size)
        assertTrue(p.single().what.contains("Fan"))
    }

    // read() — a missing key must never look like a real zero.

    @Test fun `read parses a full v4 state`() {
        val j = JSONObject()
            .put("game", "com.mojang.minecraftpe").put("binding", "tier:BALANCED").put("tier", "Balanced")
            .put("autoTdp", false).put("fps", 60).put("frameCap", 30).put("fanMode", 4)
            .put("autoTdpDefault", true)
        val a = Applied.read(j, 4)!!
        assertEquals("com.mojang.minecraftpe", a.game)
        assertEquals("tier:BALANCED", a.binding)
        assertEquals(30, a.frameCap)
        assertEquals(4, a.fanMode)
        assertTrue(a.autoTdpDefault)
    }

    /** A v2 fork has no frameCap key at all. Reading that as 0 would mean "no cap" — i.e. working. */
    @Test fun `a key the installed version does not offer reads as -1, not 0`() {
        val j = JSONObject().put("game", "com.mojang.minecraftpe").put("binding", "tier:MAX").put("fanMode", 5)
        val a = Applied.read(j, 2)!!
        assertEquals(-1, a.frameCap)
        assertEquals(-1, a.fps)
    }

    @Test fun `a null state is null, not a state full of zeroes`() {
        assertEquals(null, Applied.read(null, 4))
    }

    /** `state()` gives explicit JSON nulls for "not set", which must not become 0 either. */
    @Test fun `an explicit JSON null reads as not offered`() {
        val j = JSONObject().put("game", "com.mojang.minecraftpe").put("binding", JSONObject.NULL)
            .put("frameCap", JSONObject.NULL).put("fanMode", 5)
        val a = Applied.read(j, 4)!!
        assertEquals(null, a.binding)
        assertEquals(-1, a.frameCap)
    }

    // the all-games default

    @Test fun `the all-games default is gated on v4`() {
        val p = Applied.defaultProblems(2, null, actual(version = 3))
        assertEquals(1, p.size)
        assertTrue(p.single().message, p.single().message.contains("v${Applied.DEFAULT_VERSION}"))
    }

    @Test fun `a v4 default AutoTDP that is off is reported`() {
        assertEquals(emptyList<Applied.Problem>(), Applied.defaultProblems(2, 60, actual(autoTdpDefault = true)))
        assertEquals(1, Applied.defaultProblems(2, 60, actual(autoTdpDefault = false)).size)
    }
}