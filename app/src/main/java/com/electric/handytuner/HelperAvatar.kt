// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf

/** HandyHelper's moods: [HAPPY] picks the ^ ^ face. */
enum class Mood { IDLE, HAPPY }

/** The moonwalk's parts, in order: glide sideways, spin to show his back, ta-da pose, stroll home. */
private enum class Dance { NONE, GLIDE, SPIN, TADA, HOME }

/**
 * HandyHelper, animated in layers, back to front: legs, hands, then the console
 * (front with its face frames, or his back mid-spin). The face swaps idle/blink/
 * talk/happy on a frame clock; the hands turn around their wrists on the bounce's
 * beat; [waving] swaps the wrench for an open hand; [hops] (a counter) makes him
 * jump; [dance] (a counter) plays the moonwalk once, calling [onTaDa] at the pose.
 *
 * Layout is in the art's own pixels on a 1034×635 canvas, measured from the pieces
 * (the drawables are 2× that, for smooth edges when shown large). Per-frame motion
 * is read only inside offset{}/graphicsLayer{}, so a frame redraws, it doesn't recompose.
 */
@Composable
fun HelperAvatar(
    talking: Boolean, mood: Mood, hops: Int, waving: Boolean = false, width: Dp = 300.dp,
    dance: Int = 0, onTaDa: () -> Unit = {}, onMouth: (Offset) -> Unit = {},
) {
    // One smooth clock for everything. Old-cartoon feel comes from the timing (a slow bounce,
    // squash and stretch, swings on the beat), not from dropped frames.
    var t by remember { mutableFloatStateOf(0f) }
    var danceStart by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = (now - start) / 1e9f }
    }
    LaunchedEffect(dance) { if (dance > 0) danceStart = t }
    fun d() = if (danceStart < 0) -1f else t - danceStart            // seconds into the dance
    val part by remember { derivedStateOf { when (val x = d()) {
        in 0f..2.4f -> Dance.GLIDE; in 2.4f..3.3f -> Dance.SPIN; in 3.3f..4.6f -> Dance.TADA; in 4.6f..5.4f -> Dance.HOME
        else -> Dance.NONE                                              // before, and after it ends
    } } }
    val showBack by remember { derivedStateOf { part == Dance.SPIN && cos(((d() - 2.4f) / 0.9f) * 2f * PI.toFloat()) < 0f } }
    LaunchedEffect(part) { if (part == Dance.TADA) onTaDa() }

    var faceTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(talking) { while (true) { delay(if (talking) 170 else 150); faceTick++ } }
    val happy = mood == Mood.HAPPY || part == Dance.TADA
    val face = when {
        happy -> R.drawable.hh_front_happy
        talking -> if (faceTick % 2 == 0) R.drawable.hh_front_talk else R.drawable.hh_front_idle
        faceTick % 28 == 27 -> R.drawable.hh_front_blink                 // a blink about every 4 s
        else -> R.drawable.hh_front_idle
    }

    val beat = if (talking) 1.1f else 2.2f                             // seconds per bounce
    fun phase() = (t / beat) * 2f * PI.toFloat()
    fun bounce() = if (part == Dance.NONE) (1f - cos(phase())) / 2f else 0f
    fun pump() = sin(phase())
    fun wave() = sin(t * 2f * PI.toFloat() / 0.9f)
    /** Sideways travel: out while gliding, held through the spin and pose, back home. */
    // To his left, the screen's right: that's where the open space is (going left crossed the app's rail).
    fun travel() = when (part) {
        Dance.GLIDE -> 240f * (d() / 2.4f)
        Dance.SPIN, Dance.TADA -> 240f
        Dance.HOME -> 240f * (1f - (d() - 4.6f) / 0.8f)
        Dance.NONE -> 0f
    }
    /** Which foot is up (0 left, 1 right) and how high, 0..1, stepping every 0.3 s on the glide. */
    fun step(leg: Int): Float {
        if (part != Dance.GLIDE && part != Dance.HOME) return 0f
        val n = d() / 0.3f
        return if (n.toInt() % 2 == leg) sin((n % 1f) * PI.toFloat()) else 0f
    }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(hops) {
        if (hops == 0) return@LaunchedEffect
        hop.animateTo(-60f, tween(220))
        hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = with(density) { width.toPx() } / 1034f
    @Composable fun piece(
        res: Int, x: Int, y: Int, w: Int, h: Int, pivotX: Int = 0, pivotY: Int = 0,
        angle: () -> Float = { 0f }, lift: () -> Float = { 0f },
    ) = Image(
        painterResource(res), null,
        Modifier.offset { IntOffset((x * px).roundToInt(), ((y + lift()) * px).roundToInt()) }
            .size(with(density) { (w * px).toDp() }, with(density) { (h * px).toDp() })
            .graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX / w.toFloat(), pivotY / h.toFloat())
                rotationZ = angle()
            },
    )
    Box(
        Modifier.width(width).aspectRatio(1034f / 635f)
            // positionInRoot already carries the travel and hop offsets below, so the mouth lands
            // on his face even mid-glide. Reported in root pixels; the onboarding aims the tail here.
            .onGloballyPositioned { c ->
                onMouth(c.positionInRoot() + Offset(px * HandyHelper.MOUTH_X, px * HandyHelper.MOUTH_Y))
            }
            .offset { IntOffset((travel() * px).roundToInt(), (hop.value - bounce() * 14f).roundToInt()) }
            .graphicsLayer {
                // Squash and stretch from the feet: short and wide low in the bounce, tall and thin at the top.
                val stretch = 1f + (bounce() - 0.5f) * 0.06f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                scaleY = stretch
                // The spin: squeeze to nothing and back, twice, showing his back in between.
                val spin = if (part == Dance.SPIN) kotlin.math.abs(cos(((d() - 2.4f) / 0.9f) * 2f * PI.toFloat())) else 1f
                scaleX = (2f - stretch) * spin.coerceAtLeast(0.02f)
            },
    ) {
        // Legs first (behind the console). A lifted foot rises and tips back, like a moonwalk step.
        if (part == Dance.TADA) piece(R.drawable.hh_legs_crossed, 306, 301, 385, 331)
        else {
            piece(R.drawable.hh_leg_left, 292, 369, 191, 263, 95, 20, angle = { step(0) * 14f }, lift = { -step(0) * 34f })
            piece(R.drawable.hh_leg_right, 507, 370, 209, 262, 105, 20, angle = { step(1) * 14f }, lift = { -step(1) * 34f })
        }
        val waveNow = waving || part == Dance.TADA
        if (!showBack) {
            piece(R.drawable.hh_hand_thumb, 0, 185, 259, 277, 208, 188, angle = { -pump() * if (talking) 12f else 6f })
            if (waveNow) piece(R.drawable.hh_hand_wave, 617, 0, 280, 308, 104, 254, angle = { wave() * 20f })
        }
        if (showBack) piece(R.drawable.hh_back, 222, 195, 555, 291) else piece(face, 222, 190, 554, 300)
        // The wrench hand is the logo's own, cut from it: fingers curled toward his body, the back of the
        // hand facing out, over the console's right edge. (The AI-drawn piece was mirrored.) Pivots at the grip.
        if (!showBack && !waveNow) piece(R.drawable.hh_hand_wrench, 705, 240, 161, 263, 45, 165, angle = { pump() * if (talking) 10f else 4f })
    }
}

/**
 * The speech bubble. [line] types out a letter at a time with [onLetter] (the blip);
 * [full] shows it at once (A pressed mid-line). [onTyped] fires when it's all out.
 */
@Composable
fun SpeechBubble(line: String, full: Boolean, onLetter: () -> Unit, onTyped: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember(line) { mutableIntStateOf(0) }
    LaunchedEffect(line, full) {
        if (full) { shown = line.length; onTyped(); return@LaunchedEffect }
        while (shown < line.length) {
            val c = line[shown]
            shown++
            if (c.isLetterOrDigit() && shown % 2 == 0) onLetter()
            delay(when (c) { '.', '!', '?' -> 220L; ',' -> 110L; else -> 26L })
        }
        onTyped()
    }
    Column(modifier) {
        Text("HandyHelper", color = Hand.Blue, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = 16.sp,
            modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        Box(
            Modifier.fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(22.dp), ambientColor = Hand.Blue, spotColor = Hand.Blue)
                .background(Color(0xFF0A1428), RoundedCornerShape(22.dp))
                .border(2.dp, Hand.Blue, RoundedCornerShape(22.dp))
                .padding(horizontal = 26.dp, vertical = 20.dp),
        ) {
            // The full line, invisible, holds the bubble's size so it doesn't grow as letters appear.
            Text(line, fontSize = 22.sp, color = Color.Transparent)
            Text(line.take(shown), fontSize = 22.sp, color = Color.White)
        }
    }
}

/**
 * HandyHelper's drawn size and mouth, in the unit space the avatar's pieces are placed in
 * (see [HelperAvatar]). The mouth was found by diffing the talk and idle sprites: they differ in
 * exactly one small region, 131x85 sprite pixels at the face's centre, so its centre is the mouth.
 * The onboarding aims the bubble's tail here, so the bubble reads as his words and not floating.
 */
object HandyHelper {
    const val UNITS_W = 1034f
    const val UNITS_H = 635f
    const val MOUTH_X = 501f
    const val MOUTH_Y = 382f
    /** The mouth, as a fraction of [UNITS_W] x [UNITS_H]. */
    const val MOUTH_FX = MOUTH_X / UNITS_W
    const val MOUTH_FY = MOUTH_Y / UNITS_H
}

/**
 * The bubble's tail: a short, wide comic pointer leaving the bubble's left edge and aiming at his mouth.
 * It stops well short of his face (a long spike crossing it read as a scratch, not speech). Drawn *over*
 * the bubble: its fill hides the border where they join and its two outer sides carry the border on.
 */
@Composable
fun SpeechTail(bubbleTopLeft: Offset, bubbleHeight: Float, mouth: Offset, modifier: Modifier = Modifier) {
    // Anchors are in *root* pixels; this Canvas sits inside a padded layer, so take its own origin off.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val half = 22.dp.toPx(); val reach = 64.dp.toPx(); val inset = 3.dp.toPx()
        val cy = bubbleTopLeft.y - origin.y + bubbleHeight * 0.62f
        val x = bubbleTopLeft.x - origin.x + inset                       // starts just inside the border
        val m = mouth - origin
        val dir = Offset(m.x - x, m.y - cy).let { d -> val l = kotlin.math.hypot(d.x, d.y).coerceAtLeast(1f); Offset(d.x / l, d.y / l) }
        val tip = Offset(x + dir.x * reach, cy + dir.y * reach)
        val top = Offset(x, cy - half); val bottom = Offset(x, cy + half)
        drawPath(Path().apply { moveTo(top.x, top.y); lineTo(tip.x, tip.y); lineTo(bottom.x, bottom.y); close() }, Color(0xFF0A1428))
        val stroke = 2.dp.toPx()
        drawLine(Hand.Blue, top, tip, stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Hand.Blue, bottom, tip, stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The stage he stands on: the logo's blue disc behind him and a soft floor shadow under his feet. */
@Composable
fun HelperStage(width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.width(width).aspectRatio(HandyHelper.UNITS_W / HandyHelper.UNITS_H)) {
        val c = Offset(size.width * 0.48f, size.height * 0.46f)
        val r = size.height * 0.62f
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Hand.Blue.copy(alpha = 0.55f), Hand.Blue.copy(alpha = 0.18f), Color.Transparent), c, r * 1.25f), r * 1.25f, c)
        drawCircle(Hand.Blue.copy(alpha = 0.85f), r * 0.78f, c)
        val floorY = size.height * 0.985f
        drawOval(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
            Offset(size.width * 0.48f, floorY), size.width * 0.28f),
            topLeft = Offset(size.width * 0.20f, floorY - size.height * 0.05f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.10f))
    }
}
