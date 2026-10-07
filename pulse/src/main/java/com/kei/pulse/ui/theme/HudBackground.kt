package com.kei.pulse.ui.theme

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.kei.pulse.model.PulseThemeId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Cheap deterministic hash → 0..1, used to vary every leaf flight so no fall ever repeats. */
private fun hash(n: Float): Float {
    val s = sin(n) * 43758.547f
    return s - floor(s)
}

/** Reusable Path scratch — rewound and refilled each frame instead of reallocating ~96 paths. */
private class PathScratch {
    val fill = Path()
    val line = Path()
    val leaf = Path()
}

/** Washi grain points scaled to the current canvas size, rebuilt only when the size changes. */
private class GrainCache {
    var key = 0L
    var light: List<Offset> = emptyList()
    var dark: List<Offset> = emptyList()
}

/**
 * The PULSE atmosphere: a procedurally-seeded constellation telemetry field. A fresh random
 * seed on every launch lays out a unique set of drifting nodes; each gently orbits its anchor
 * (seamless, so there is no obvious loop), faint links connect nearby nodes, and some nodes
 * emit slow expanding PULSE rings. Deliberately low-contrast so content stays readable.
 * Pure Compose drawing, themed from the active palette.
 */
private class HudNode(
    val x: Float,
    val y: Float,
    val ampX: Float,
    val ampY: Float,
    val phaseX: Float,
    val phaseY: Float,
    val freqX: Int,
    val freqY: Int,
    val emitter: Boolean,
    val emitSpeed: Int,
    val emitPhase: Float,
)

/** A procedurally-placed arc of accretion-disk material that orbits the black hole (Ad Astra). */
private class DiskStreak(
    val radiusFrac: Float, // 0.42..1.0 of the disk radius
    val baseAngle: Float,  // 0..1 start phase (× 2π)
    val arcLen: Float,     // length of the streak in radians
    val speed: Float,      // angular speed — inner streaks orbit faster (differential rotation)
    val width: Float,
    val alpha: Float,
)

/**
 * A leaf burst from the wave crest (Ronin). Only the cadence is fixed — every individual
 * flight re-derives its velocity, lane, tumble, and tint from a hash of the flight number,
 * so no two falls are ever the same.
 */
private class SprayLeaf(
    val duration: Float, // seconds per flight (5..11)
    val phase: Float,
    val size: Float,     // fraction of the min screen dimension
    val tint: Float,     // base tint, shifted per flight
    val alpha: Float,
)

/**
 * One translucent swell layer of the Ronin great wave — stacked-sine fluid geometry. The
 * angular speeds are seeded continuous values (rad/s), mutually incommensurate, so the
 * combined wave motion never visibly repeats.
 */
private class WaveLayer(
    val phase1: Float,  // primary swell phase offset
    val phase2: Float,  // ripple phase offset
    val phase3: Float,  // amplitude-breathing phase offset
    val omega1: Float,  // primary swell speed (rad/s)
    val omega2: Float,  // ripple speed (rad/s)
    val omega3: Float,  // amplitude-breathing speed (rad/s)
    val jitter: Float,  // per-layer amplitude variation (the "noise" offset)
)

/** A drifting autumn leaf for the Ronin theme — falls, sways, and tumbles. */
private class Leaf(
    val x: Float,        // base horizontal position (0..1)
    val size: Float,     // fraction of the min screen dimension
    val fall: Float,     // vertical speed
    val swayAmp: Float,  // horizontal sway amplitude
    val phase: Float,    // start phase for fall + sway
    val rot: Float,      // tumble speed / direction
    val tint: Float,     // 0 = crimson .. 1 = amber
    val alpha: Float,
)

@Composable
fun HudBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val baseTop = scheme.background
    val baseMid = scheme.surfaceContainerLow
    val accent = scheme.primary
    val accent2 = scheme.secondary
    val warm = scheme.tertiary
    val themeId = LocalPulseThemeId.current
    val heat = LocalThermalHeat.current

    val nodes = remember {
        val rng = Random(System.nanoTime())
        val count = 16 + rng.nextInt(7) // 16..22, varies per launch
        List(count) {
            HudNode(
                x = rng.nextFloat(),
                y = rng.nextFloat(),
                ampX = 0.012f + rng.nextFloat() * 0.030f,
                ampY = 0.012f + rng.nextFloat() * 0.030f,
                phaseX = rng.nextFloat(),
                phaseY = rng.nextFloat(),
                freqX = 1 + rng.nextInt(2),
                freqY = 1 + rng.nextInt(2),
                emitter = rng.nextFloat() < 0.32f,
                emitSpeed = 1 + rng.nextInt(3),
                emitPhase = rng.nextFloat(),
            )
        }
    }

    // Procedural accretion-disk streaks for Ad Astra — a fresh swirl on every launch.
    val streaks = remember {
        val rng = Random(System.nanoTime())
        List(28) {
            val rf = 0.42f + rng.nextFloat() * 0.58f
            DiskStreak(
                radiusFrac = rf,
                baseAngle = rng.nextFloat(),
                arcLen = 0.25f + rng.nextFloat() * 1.0f,
                // Inner streaks orbit faster (differential shear). Whole revolutions per cycle so
                // the animation loop wraps seamlessly — fractional speeds snap at the loop point.
                speed = kotlin.math.round(1f / rf),
                width = 1.0f + rng.nextFloat() * 2.0f,
                alpha = 0.06f + rng.nextFloat() * 0.14f,
            )
        }
    }

    // Procedural Ronin set — a red ink sun, the leaf-foam spray off the wave, drifting leaves.
    val roninSun = remember {
        val rng = Random(System.nanoTime())
        Triple(0.5f + (rng.nextFloat() - 0.5f) * 0.5f, 0.24f + rng.nextFloat() * 0.12f, 0.20f + rng.nextFloat() * 0.07f)
    }
    val roninSpray = remember {
        val rng = Random(System.nanoTime())
        List(30) {
            SprayLeaf(
                duration = 5f + rng.nextFloat() * 6f,
                phase = rng.nextFloat(),
                size = 0.011f + rng.nextFloat() * 0.016f,
                tint = rng.nextFloat(),
                alpha = 0.35f + rng.nextFloat() * 0.45f,
            )
        }
    }
    val roninLayers = remember {
        val rng = Random(System.nanoTime())
        List(26) {
            WaveLayer(
                phase1 = rng.nextFloat(),
                phase2 = rng.nextFloat(),
                phase3 = rng.nextFloat(),
                omega1 = 0.15f + rng.nextFloat() * 0.30f,
                omega2 = 0.45f + rng.nextFloat() * 0.45f,
                omega3 = 0.05f + rng.nextFloat() * 0.12f,
                jitter = rng.nextFloat(),
            )
        }
    }
    // Static washi-paper grain, seeded per launch: one light speckle set, one dark.
    val roninGrain = remember {
        val rng = Random(System.nanoTime())
        Pair(
            List(200) { Offset(rng.nextFloat(), rng.nextFloat()) },
            List(170) { Offset(rng.nextFloat(), rng.nextFloat()) },
        )
    }
    val grainCache = remember { GrainCache() }
    val scratch = remember { PathScratch() }
    val leaves = remember {
        val rng = Random(System.nanoTime())
        List(14) {
            Leaf(
                x = rng.nextFloat(),
                size = 0.013f + rng.nextFloat() * 0.017f,
                // Whole falls/rotations per animation cycle so the loop wraps seamlessly.
                fall = (1 + rng.nextInt(2)).toFloat(),
                swayAmp = 0.02f + rng.nextFloat() * 0.05f,
                phase = rng.nextFloat(),
                rot = ((if (rng.nextBoolean()) 1 else -1) * (1 + rng.nextInt(2))).toFloat(),
                tint = rng.nextFloat(),
                alpha = 0.25f + rng.nextFloat() * 0.4f,
            )
        }
    }

    // 30fps ambient clock — a slow background gains nothing from 120Hz, and stepping the
    // clock (instead of an infinite transition) cuts the draw work ~4x on these panels.
    // Emits absolute seconds: Ronin's motion never loops; the other themes derive their
    // seamless 22s loop from it. Wraps every 2h (one invisible frame) for float precision.
    val clock = produceState(0f) {
        while (true) {
            value = (SystemClock.uptimeMillis() % 7_200_000L) / 1000f
            delay(33)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val tau = clock.value
                val t = (tau % 22f) / 22f
                val w = size.width
                val h = size.height
                val twoPi = (2.0 * PI).toFloat()
                val linkDist = minOf(w, h) * 0.22f

                // base wash shared by every theme
                drawRect(brush = Brush.verticalGradient(0f to baseTop, 0.5f to baseMid, 1f to baseTop))

                when (themeId) {
                    PulseThemeId.ADASTRA ->
                        // Ad Astra: a tilted accretion disk instead of the constellation field.
                        drawAccretionDisk(w = w, h = h, t = t, twoPi = twoPi, heat = heat, ice = accent, amber = warm, streaks = streaks)
                    PulseThemeId.CYBERPUNK ->
                        // Cyberpunk: a neon perspective grid + CRT scanline instead of the constellation.
                        drawNeonGrid(w = w, h = h, t = t, cyan = accent, magenta = accent2)
                    PulseThemeId.RONIN ->
                        // Ronin: "Autumn-on-Ink" — a layered fluid-geometry great wave with
                        // glowing leaf spray, ukiyo-e striations, and washi grain.
                        drawRoninInk(
                            w = w, h = h, tau = tau, twoPi = twoPi,
                            crimson = accent, steel = accent2,
                            sun = roninSun, spray = roninSpray, leaves = leaves,
                            layers = roninLayers, grain = roninGrain,
                            grainCache = grainCache, scratch = scratch,
                        )
                    else -> {
                    // a soft, slowly breathing central glow
                    val glowPulse = 0.06f + 0.02f * sin(t * twoPi)
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = glowPulse), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.38f),
                            radius = w * 0.85f,
                        ),
                    )

                    // resolve live node positions
                    val pts = nodes.map { n ->
                        val px = (n.x + n.ampX * sin((n.freqX * t + n.phaseX) * twoPi)) * w
                        val py = (n.y + n.ampY * sin((n.freqY * t + n.phaseY) * twoPi)) * h
                        Offset(px, py)
                    }

                    // faint links between nearby nodes
                    for (i in pts.indices) {
                        for (j in i + 1 until pts.size) {
                            val dx = pts[i].x - pts[j].x
                            val dy = pts[i].y - pts[j].y
                            val d = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (d < linkDist) {
                                val a = (1f - d / linkDist) * 0.085f
                                drawLine(accent.copy(alpha = a), pts[i], pts[j], strokeWidth = 1f)
                            }
                        }
                    }

                    // emitter pulse rings + node dots
                    nodes.forEachIndexed { idx, n ->
                        val p = pts[idx]
                        if (n.emitter) {
                            val prog = (n.emitSpeed * t + n.emitPhase) % 1f
                            val a = (1f - prog) * 0.07f
                            if (a > 0.004f) {
                                drawCircle(
                                    color = accent2.copy(alpha = a),
                                    radius = prog * (linkDist * 1.6f),
                                    center = p,
                                    style = Stroke(width = 1.5f),
                                )
                            }
                            drawCircle(accent.copy(alpha = 0.30f), radius = 2.6f, center = p)
                        } else {
                            drawCircle(accent.copy(alpha = 0.16f), radius = 1.8f, center = p)
                        }
                    }

                    // a faint travelling PULSE wave low in the frame — the signature, kept subtle
                    drawPulseWave(
                        w = w,
                        baseY = h * 0.82f,
                        twoPi = twoPi,
                        cx = t * (w * 1.3f) - w * 0.15f,
                        sigma = w * 0.14f,
                        wavelength = w * 0.06f,
                        amp = h * 0.05f,
                        color = accent,
                    )
                    }
                }

                // edge vignette to focus the centre and lift text legibility
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.40f)),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = max(w, h) * 0.70f,
                    ),
                )
            },
    ) {
        content()
    }
}

/** A localized travelling pulse packet centred at [cx], flat baseline elsewhere. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPulseWave(
    w: Float,
    baseY: Float,
    twoPi: Float,
    cx: Float,
    sigma: Float,
    wavelength: Float,
    amp: Float,
    color: Color,
) {
    val path = Path()
    var x = 0f
    var first = true
    val twoSigmaSq = 2f * sigma * sigma
    while (x <= w) {
        val d = x - cx
        val env = exp(-(d * d) / twoSigmaSq)
        val y = baseY + sin(d / wavelength * twoPi) * amp * env
        if (first) {
            path.moveTo(x, y)
            first = false
        } else {
            path.lineTo(x, y)
        }
        x += 3f
    }
    drawPath(path, color = color.copy(alpha = 0.05f), style = Stroke(width = 8f))
    drawPath(path, color = color.copy(alpha = 0.22f), style = Stroke(width = 1.8f))
    drawCircle(color.copy(alpha = 0.45f), radius = 3.2f, center = Offset(cx, baseY))
}

/**
 * Ad Astra's signature: a tilted Gargantua-style accretion disk with a lensed halo arched over the
 * top of the event horizon. Pure Canvas strokes driven by the one shared rotation — no shaders or
 * bitmaps — so it costs no more than the constellation field. Colour temperature ramps inner→outer
 * and is pushed warmer by [heat] (the active power tier): icy/calm when idle, amber→corona ignition
 * at max. Sits high in the frame at low alpha so the telemetry below stays legible.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAccretionDisk(
    w: Float,
    h: Float,
    t: Float,
    twoPi: Float,
    heat: Float,
    ice: Color,
    amber: Color,
    streaks: List<DiskStreak>,
) {
    val cx = w * 0.5f
    val cy = h * 0.38f
    val r = maxOf(w, h) * 0.5f          // large — the hole dominates the frame
    val tilt = 0.62f                    // near face-on, like the reference
    val coreR = r * 0.42f
    val corona = Color(0xFFFFD54F)
    val breathe = 0.85f + 0.15f * sin(t * twoPi)

    // Colour by orbit radius: cool ice near the photon ring → warm amber/corona in the outer
    // reaches, like the reference. heat (active tier) pushes the whole disk warmer.
    fun temp(radiusFrac: Float): Color {
        val f = ((radiusFrac - 0.42f) / 0.58f).coerceIn(0f, 1f)
        val warmed = (f + 0.35f * heat).coerceIn(0f, 1f)
        return if (warmed < 0.5f) lerp(ice, amber, warmed * 2f) else lerp(amber, corona, (warmed - 0.5f) * 2f)
    }

    // 0) outer furnace glow — a warm haze radiating from the disk
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(amber, corona, heat).copy(alpha = (0.05f + 0.06f * heat) * breathe),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = r * 1.2f,
        ),
        radius = r * 1.2f,
        center = Offset(cx, cy),
    )

    // 1) swirling accretion-disk material — procedural arcs orbiting with differential rotation
    for (s in streaks) {
        val rx = s.radiusFrac * r
        val ry = rx * tilt
        val ang = (s.baseAngle + t * s.speed) * twoPi
        val startDeg = (Math.toDegrees(ang.toDouble()).toFloat()) % 360f
        val sweepDeg = Math.toDegrees(s.arcLen.toDouble()).toFloat()
        val leftBright = 1f - (cos(ang) + 1f) / 2f          // Doppler: approaching side brighter
        val a = (s.alpha * (0.45f + 0.95f * leftBright) * breathe).coerceAtMost(0.5f)
        drawArc(
            color = temp(s.radiusFrac).copy(alpha = a),
            startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
            topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = s.width),
        )
    }

    // 2) lensed halo standing up over the top of the sphere
    val haloRx = r * 0.72f
    val haloRy = r * 0.54f
    drawArc(
        color = temp(0.5f).copy(alpha = 0.22f * breathe),
        startAngle = 196f, sweepAngle = 148f, useCenter = false,
        topLeft = Offset(cx - haloRx, cy - haloRy), size = Size(haloRx * 2f, haloRy * 2f),
        style = Stroke(width = 2.4f),
    )

    // 3) event horizon — a large true-black sphere
    drawCircle(Color.Black, radius = coreR, center = Offset(cx, cy))

    // 4) the brilliant photon ring — the signature, brightest element, with a soft bloom
    drawCircle(
        color = ice.copy(alpha = 0.22f * breathe),
        radius = coreR * 1.08f, center = Offset(cx, cy),
        style = Stroke(width = 6f),
    )
    drawCircle(
        color = lerp(ice, corona, heat * 0.5f).copy(alpha = 0.72f * breathe),
        radius = coreR * 1.03f, center = Offset(cx, cy),
        style = Stroke(width = 2.6f),
    )

    // 5) the front lip of the disk wrapping in front of the sphere's lower edge
    val frontR = coreR * 1.4f
    drawArc(
        color = temp(0.7f).copy(alpha = 0.30f * breathe),
        startAngle = 22f, sweepAngle = 136f, useCenter = false,
        topLeft = Offset(cx - frontR, cy - frontR * tilt), size = Size(frontR * 2f, frontR * tilt * 2f),
        style = Stroke(width = 2.4f),
    )

    // 6) hot spots orbiting the disk, much brighter on the approaching (left) side — Doppler
    for (k in 0 until 5) {
        val ang = t * twoPi + k * (twoPi / 5f)
        val px = cx + r * 0.8f * cos(ang)
        val py = cy + r * 0.8f * tilt * sin(ang)
        val leftBright = 1f - (cos(ang) + 1f) / 2f
        val a = 0.12f + 0.40f * leftBright
        drawCircle(temp(0.8f).copy(alpha = a), radius = 2.6f, center = Offset(px, py))
    }
}

/**
 * Cyberpunk's signature: a synthwave perspective grid scrolling toward a neon horizon, with a
 * slow CRT scanline sweep. Pure Canvas lines on the one shared rotation — no shaders — so it
 * stays in the same resource class as the constellation field. Kept low-alpha for legibility.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeonGrid(
    w: Float,
    h: Float,
    t: Float,
    cyan: Color,
    magenta: Color,
) {
    val horizon = h * 0.52f
    val vp = Offset(w * 0.5f, horizon)

    // converging vertical lines from the bottom edge up to the vanishing point
    val cols = 12
    for (i in 0..cols) {
        val fx = i / cols.toFloat()
        val bottomX = (fx - 0.5f) * w * 2.4f + w * 0.5f
        drawLine(cyan.copy(alpha = 0.10f), Offset(bottomX, h), vp, strokeWidth = 1f)
    }

    // horizontal grid lines scrolling toward the viewer; perspective bunches them near the horizon
    val rows = 16
    val scroll = (t * 3f) % 1f
    for (i in 0 until rows) {
        val d = ((i / rows.toFloat()) + scroll) % 1f
        val yy = horizon + (h - horizon) * (d * d)
        val a = 0.04f + 0.14f * d
        val col = if (i % 2 == 0) cyan else magenta
        drawLine(col.copy(alpha = a), Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
    }

    // a soft neon wash above the horizon + the bright horizon line itself
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, magenta.copy(alpha = 0.10f)),
            startY = horizon - h * 0.18f,
            endY = horizon,
        ),
        topLeft = Offset(0f, horizon - h * 0.18f),
        size = Size(w, h * 0.18f),
    )
    drawLine(cyan.copy(alpha = 0.55f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = 1.6f)

    // a CRT scanline sweeping down the screen (whole sweeps per cycle = seamless loop)
    val scanY = ((t * 2f) % 1f) * h
    drawRect(
        color = cyan.copy(alpha = 0.05f),
        topLeft = Offset(0f, scanY - 10f),
        size = Size(w, 20f),
    )
    drawLine(cyan.copy(alpha = 0.20f), Offset(0f, scanY), Offset(w, scanY), strokeWidth = 1.5f)
}

/**
 * Ronin: "Autumn-on-Ink" — Hokusai's great wave reimagined as layered fluid geometry.
 *
 * The wave is no single shape: 26 translucent swell layers, each a sampled sine-stack whose
 * phase, speed, and amplitude are seeded per layer (the noise offsets), rise under a gaussian
 * envelope into a great crest that rolls and morphs organically. Thin contour strokes follow
 * the layer curves like ukiyo-e woodblock line-work, the topmost crest line glows additively,
 * and a particle system bursts glowing autumn leaves off the crest peak with wind + gravity.
 * A static seeded speckle field lays washi-paper grain over everything. Palette: deep indigo
 * swells warming through burnt sienna into muted crimson; gold/orange/crimson leaves. All
 * speeds are whole cycles per loop, so the 22s animation wraps seamlessly. Pure Canvas.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoninInk(
    w: Float,
    h: Float,
    tau: Float, // absolute seconds — Ronin's motion never loops
    twoPi: Float,
    crimson: Color,
    steel: Color,
    sun: Triple<Float, Float, Float>,
    spray: List<SprayLeaf>,
    leaves: List<Leaf>,
    layers: List<WaveLayer>,
    grain: Pair<List<Offset>, List<Offset>>,
    grainCache: GrainCache,
    scratch: PathScratch,
) {
    val minWH = minOf(w, h)
    val gold = Color(0xFFE8B84B)
    val orange = Color(0xFFFF7A3C)
    val cream = Color(0xFFE8D9B0)
    val indigoDeep = Color(0xFF1B2342)
    val sienna = Color(0xFF7A3B24)
    val crimsonMuted = Color(0xFF771B22)

    fun leafTint(f: Float): Color =
        if (f < 0.5f) lerp(crimson, orange, f * 2f) else lerp(orange, gold, (f - 0.5f) * 2f)

    fun swellColor(d: Float): Color =
        if (d < 0.55f) lerp(indigoDeep, sienna, d / 0.55f) else lerp(sienna, crimsonMuted, (d - 0.55f) / 0.45f)

    // 1) ink-wash sky: a whisper of indigo falling away to the void
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF141B30).copy(alpha = 0.50f),
            1f to Color.Transparent,
            endY = h * 0.45f,
        ),
        size = Size(w, h * 0.45f),
    )

    // 2) the red sun, glowing over the open sky
    val pulse = 0.9f + 0.1f * sin(tau * 0.286f)
    val scx = (0.70f + (sun.first - 0.5f) * 0.2f) * w
    val scy = sun.second * 0.75f * h
    val sr = sun.third * 0.8f * h
    drawCircle(
        brush = Brush.radialGradient(
            listOf(crimson.copy(alpha = 0.40f * pulse), crimson.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(scx, scy),
            radius = sr * 2.3f,
        ),
        radius = sr * 2.3f,
        center = Offset(scx, scy),
    )
    drawCircle(crimson.copy(alpha = 0.30f * pulse), radius = sr, center = Offset(scx, scy))
    drawCircle(crimson.copy(alpha = 0.45f), radius = sr, center = Offset(scx, scy), style = Stroke(width = 2.5f))

    // 3) the great wave — stacked translucent swell layers under a wandering crest envelope.
    // Two incommensurate drift sines keep the peak's path from ever retracing itself.
    val peakX = 0.30f + 0.05f * sin(tau * 0.10f) + 0.025f * sin(tau * 0.043f)
    val sigma = 0.17f
    fun env(x: Float): Float {
        val dx = x - peakX
        return exp(-(dx * dx) / (2f * sigma * sigma))
    }

    val n = layers.size
    val steps = 27
    val fill = scratch.fill
    val line = scratch.line
    for ((i, ly) in layers.withIndex()) {
        val d = i / (n - 1f) // 0 = farthest/highest layer
        val baseY = 0.40f + 0.63f * d * (0.72f + 0.28f * d)
        val breath = 0.8f + 0.4f * ly.jitter * (0.7f + 0.3f * sin(tau * ly.omega3 + ly.phase3 * twoPi))
        val amp = (0.055f + 0.245f * (1f - d)) * breath
        val contour = i == 0 || i % 4 == 0 || i % 4 == 2
        fill.rewind()
        if (contour) line.rewind()
        for (s in 0..steps) {
            val x = s / steps.toFloat()
            val lift = env(x) * (0.55f + 0.45f * sin((x * 1.4f + ly.phase1) * twoPi + tau * ly.omega1))
            val ripple = 0.012f * sin((x * 6f + ly.phase2) * twoPi + tau * ly.omega2)
            val y = (baseY - amp * lift - ripple) * h
            if (s == 0) {
                fill.moveTo(0f, y)
                if (contour) line.moveTo(0f, y)
            } else {
                fill.lineTo(x * w, y)
                if (contour) line.lineTo(x * w, y)
            }
        }
        fill.lineTo(w, h + 2f)
        fill.lineTo(0f, h + 2f)
        fill.close()
        drawPath(fill, color = swellColor(d).copy(alpha = 0.13f))
        when {
            // the topmost curve is the crest line — gold, additive, alive
            i == 0 -> drawPath(line, color = gold.copy(alpha = 0.30f), style = Stroke(width = 2f), blendMode = BlendMode.Plus)
            // woodblock striations: fine contour line-work following the swells
            i % 4 == 0 -> drawPath(line, color = cream.copy(alpha = 0.13f), style = Stroke(width = 1.2f))
            i % 4 == 2 -> drawPath(line, color = steel.copy(alpha = 0.09f), style = Stroke(width = 0.8f))
        }
    }

    // 4) leaf spray off the crest — every flight re-rolls velocity/lane/tumble/tint from a
    // hash of its flight number, so no fall ever repeats. Respawns happen while faded out.
    val crest = layers[0]
    val crestLift = env(peakX) * (0.55f + 0.45f * sin((peakX * 1.4f + crest.phase1) * twoPi + tau * crest.omega1))
    val crestY = 0.40f - 0.30f * (0.8f + 0.4f * crest.jitter) * crestLift
    spray.forEachIndexed { idx, s ->
        val ft = tau / s.duration + s.phase
        val p = ft - floor(ft)
        val flight = floor(ft)
        val h1 = hash(idx * 57.31f + flight * 0.713f)
        val h2 = hash(idx * 12.99f + flight * 1.618f)
        val h3 = hash(idx * 93.71f + flight * 0.374f)
        val vx = -0.06f + 0.34f * h1
        val vy = -(0.05f + 0.15f * h2)
        val x = (peakX + (h3 - 0.5f) * 0.12f + vx * p) * w
        val y = (crestY + vy * p + 1.4f * p * p) * h
        if (y > h + 20f) return@forEachIndexed
        val fade = when {
            p < 0.08f -> p / 0.08f
            p > 0.75f -> (1f - p) / 0.25f
            else -> 1f
        }
        val a = s.alpha * fade
        if (a <= 0.02f) return@forEachIndexed
        val spin = (if (h1 > 0.5f) 1f else -1f) * (1.5f + 2f * h2)
        val tint = (s.tint + flight * 0.318f).let { it - floor(it) }
        rotate(degrees = p * spin * 360f, pivot = Offset(x, y)) {
            drawPath(
                leafPathInto(scratch.leaf, x, y, s.size * minWH),
                color = leafTint(tint).copy(alpha = a),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // 5) ambient leaves — each fall picks a fresh lane, sway, and tint via the same hashing
    leaves.forEachIndexed { idx, lf ->
        val duration = 22f / lf.fall
        val ft = tau / duration + lf.phase
        val p = ft - floor(ft)
        val fall = floor(ft)
        val hx = hash(idx * 31.7f + fall * 0.591f)
        val hr = hash(idx * 71.3f + fall * 1.247f)
        val ly = p * (h + 40f) - 20f
        val lx = (0.02f + 0.96f * hx + lf.swayAmp * sin((p * 2f + lf.phase) * twoPi)) * w
        val tint = (lf.tint + fall * 0.318f).let { it - floor(it) }
        val spin = (if (hr > 0.5f) 1f else -1f) * (1f + 2f * hr)
        rotate(degrees = p * spin * 360f, pivot = Offset(lx, ly)) {
            drawPath(
                leafPathInto(scratch.leaf, lx, ly, lf.size * minWH),
                color = leafTint(tint).copy(alpha = lf.alpha),
            )
        }
    }

    // 6) washi-paper grain: static speckles, scaled once per canvas size and cached
    val sizeKey = (w.toLong() shl 32) or h.toLong()
    if (grainCache.key != sizeKey) {
        grainCache.key = sizeKey
        grainCache.light = grain.first.map { Offset(it.x * w, it.y * h) }
        grainCache.dark = grain.second.map { Offset(it.x * w, it.y * h) }
    }
    drawPoints(
        points = grainCache.light,
        pointMode = PointMode.Points,
        color = cream.copy(alpha = 0.03f),
        strokeWidth = 1.6f,
    )
    drawPoints(
        points = grainCache.dark,
        pointMode = PointMode.Points,
        color = Color.Black.copy(alpha = 0.05f),
        strokeWidth = 1.8f,
    )
}

/** Fills [path] with a small pointed-oval leaf centred at (cx, cy) — reused, never allocated. */
private fun leafPathInto(path: Path, cx: Float, cy: Float, size: Float): Path {
    path.rewind()
    path.moveTo(cx, cy - size)
    path.quadraticBezierTo(cx + size * 0.62f, cy, cx, cy + size)
    path.quadraticBezierTo(cx - size * 0.62f, cy, cx, cy - size)
    path.close()
    return path
}
