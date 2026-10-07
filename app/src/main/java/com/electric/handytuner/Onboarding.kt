// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Everything setup depends on, read live each time the app comes back to the front. */
private data class SetupStatus(
    val a11y: Boolean, val usage: Boolean, val notif: Boolean,
    val root: Boolean, val cocoon: Boolean,
)

/**
 * First-run setup, guided by HandyHelper: he talks (typed speech bubbles with
 * blips), reacts when a step gets done, and each step ends in an action card
 * with its live status. Music and sounds come from [SoundKit]. A advances, B
 * goes back. [resumes] changes whenever the app returns to the front, so a
 * permission granted in Settings is noticed at once.
 */
@Composable
fun Onboarding(ctx: Context, resumes: Int, hudHeld: Boolean, menuHeld: Boolean, askNotifications: () -> Unit, onDone: () -> Unit) {
    var st by remember { mutableStateOf<SetupStatus?>(null) }
    LaunchedEffect(resumes) {
        st = withContext(Dispatchers.IO) {
            fun installed(p: String) = runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
            SetupStatus(
                Grants.granted(ctx, Grants.Kind.ACCESSIBILITY), Grants.granted(ctx, Grants.Kind.USAGE),
                Grants.granted(ctx, Grants.Kind.NOTIFICATIONS), PServer.run("echo ok") == "ok",
                installed(COCOON),
            )
        }
    }

    // Music and sounds: start with the onboarding, pause when the app leaves the front, gone after.
    val sound = remember { SoundKit(ctx).apply { load() } }
    var muted by remember { mutableStateOf(sound.muted) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        sound.startMusic()
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) sound.pauseMusic()
            if (e == Lifecycle.Event.ON_RESUME) sound.startMusic()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); sound.release() }
    }

    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun web(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    var style by remember { mutableStateOf(HudStyle.load(ctx)) }
    // The "try it" step: a game the overlay spotted after onboarding started counts.
    val openedAt = remember { System.currentTimeMillis() }
    var spotted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumes) {
        spotted = withContext(Dispatchers.IO) {
            runCatching { java.io.File(ctx.filesDir, "last_game").readLines() }.getOrNull()
                ?.takeIf { it.size >= 2 && (it[0].toLongOrNull() ?: 0) > openedAt }?.get(1)
        }
    }
    val s = st ?: SetupStatus(false, false, false, true, false)

    /** [lines] said by HandyHelper, then the card; [done] null = nothing to check; [cheer] when done flips true. */
    class Step(
        val lines: List<String>, val done: Boolean? = null, val cheer: String? = null,
        val action: String? = null, val act: () -> Unit = {}, val next: String = "Next",
        val extra: (@Composable () -> Unit)? = null,
        val alt: String? = null, val altAct: () -> Unit = {},
    )

    /**
     * One permission step, said and wired from [Grants] so it can't drift from the Diagnostics row.
     * [ask] wins over the Settings page where the app can ask for it itself (notifications).
     */
    fun grant(kind: Grants.Kind, done: Boolean, ask: (() -> Unit)? = null): Step {
        val g = Grants.byKind(kind)
        val escape = Grants.escape(ctx, kind)
        return Step(
            if (done) listOf("Nice, ${g.title.lowercase()} is already on.") else g.steps,
            done, g.cheer, if (done) null else g.button,
            {
                if (ask != null) ask()
                else Grants.intent(ctx, kind)?.let { open(it) }
            },
            alt = if (done) null else escape?.let { Grants.escapeButton },
            altAct = { escape?.let { open(it) } },
        )
    }

    val steps = listOf(
        Step(listOf("Hi! I'm HandyHelper.", "I live in your Odin and help your games run cool, smooth and connected.",
            "Let's get you set up. It only takes a minute!"), next = "Let's go!"),
        Step(listOf("Here's what I can do.", "A HUD showing FPS, ping, and whatever is slowing a game down.",
            "A Quick Menu over any game: brightness, screenshots, AFK and more.",
            "And profiles that save battery or lower ping on their own.")),
        grant(Grants.Kind.ACCESSIBILITY, s.a11y),
        grant(Grants.Kind.USAGE, s.usage),
        grant(Grants.Kind.NOTIFICATIONS, s.notif, askNotifications),
        Step(if (s.root) listOf("Good news: your Odin's built-in helper answers me.",
            "So FPS, brightness, refresh rate and screenshots will all work.")
            else listOf("Your Odin's built-in helper isn't answering me.",
                "I'll still work, but FPS and screenshots won't. Nothing for you to do."), s.root),
        Step(if (s.cocoon) listOf("And Cocoon's here too!",
            "Start a game from Cocoon and I'll know exactly which one, even Windows games.")
            else listOf("Cocoon is a comfy launcher for all your games.",
                "Start games from it and I'll know exactly which one you're playing.",
                "Grab the newest APK from its release page."),
            s.cocoon, "Cocoon's in! Launch away.", "Get Cocoon", { web("https://github.com/inssekt/CocoonFE/releases") }),
        Step(if (s.cocoon) listOf("Let's test it! Open Cocoon and start any game.",
                "Play for a few seconds, then come back here. I'll tell you what I spotted.")
            else listOf("Once Cocoon is in, start any game from it and I'll spot which one.",
                "You can skip this for now."),
            spotted != null, spotted?.let { "I spotted $it! Profiles and sessions will follow that game." },
            if (s.cocoon) "Open Cocoon" else null, { ctx.packageManager.getLaunchIntentForPackage(COCOON)?.let { open(it) } }),
        Step(listOf("Now the fun part: pick my HUD color!", "That's the glow you'll see in your games."),
            extra = { AccentPicker(style) { style = style.copy(accent = it); HudStyle.save(ctx, style); sound.play("select") } }),
        Step(listOf("Let's try a hotkey.", "Hold both back buttons and press both sticks in, all four at once."),
            hudHeld, "You got it! That shows or hides the HUD in any game."),
        Step(listOf("One more: press both sticks in and R1 together."),
            menuHeld, "Perfect! That opens the Quick Menu over your game."),
        Step(listOf("You're all set!", "Open a game and try your hotkeys. I'll be here if you need me."),
            extra = { SetupSummary(s) }, next = "Start using HandyTuner"),
    )

    var step by remember { mutableIntStateOf(0) }
    var line by remember { mutableIntStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(false) }
    var cheer by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf(Mood.IDLE) }
    var hops by remember { mutableIntStateOf(0) }
    var dance by remember { mutableIntStateOf(0) }
    val cur = steps[step]
    // The one button left on a card does the step's action; A continues, so the hint has to stay.
    val needs = cur.action != null && cur.done != true
    val said = cheer ?: cur.lines[line.coerceAtMost(cur.lines.lastIndex)]
    val atCard = typed && (cheer != null || line >= cur.lines.lastIndex)

    // He reacts the moment a step turns done (back from Settings, an install, a hotkey press).
    var wasDone by remember(step) { mutableStateOf(cur.done) }
    LaunchedEffect(cur.done) {
        if (cur.done == true && wasDone == false && cur.cheer != null) {
            cheer = cur.cheer; full = false; typed = false
            sound.play(if (step >= 10) "hotkey" else "success"); hops++; mood = Mood.HAPPY
            delay(1_600); mood = Mood.IDLE
        }
        wasDone = cur.done
    }

    fun go(to: Int) { step = to.coerceIn(0, steps.lastIndex); line = 0; full = false; typed = false; cheer = null }
    fun advance() {
        when {
            !typed -> full = true
            !atCard -> { line++; full = false; typed = false; sound.play("select", 0.3f) }
            // At the card, A moves on: to the next step, or out of setup on the last one. It used to
            // depend on a "Next" button holding focus, so a lost focus or a clipped card (BUGS.md #17)
            // left the run with no way forward.
            atCard -> if (step == steps.lastIndex) { sound.play("success"); onDone() } else { sound.play("select"); go(step + 1) }
        }
    }
    BackHandler {
        sound.play("back", 0.4f)
        if (line > 0 && cheer == null) { line--; full = true } else if (step > 0) go(step - 1)
    }

    val bubbleFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    // Only the action button takes focus; with nothing to press, A goes to the bubble, which continues.
    LaunchedEffect(step, atCard) {
        runCatching { if (atCard && needs) cardFocus.requestFocus() else bubbleFocus.requestFocus() }
    }
    // The finale: a moonwalk once "You're all set!" has been said.
    LaunchedEffect(step, typed) { if (step == steps.lastIndex && typed && line == 0) dance++ }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF06214A), Color.Black), radius = 1400f))) {
        // Top right: progress dots and the mute switch.
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Done steps blue, this one a wider pill, the rest dim.
            steps.indices.forEach { i ->
                Box(Modifier.height(9.dp).width(if (i == step) 28.dp else 9.dp)
                    .background(if (i <= step) Hand.Blue else Color(0xFF2A3654), CircleShape))
            }
            Text(if (muted) "🔇" else "🔊", fontSize = 22.sp, modifier = Modifier.padding(start = 14.dp).glowFocus(RoundedCornerShape(8.dp))
                .clickable { sound.muted = !sound.muted; muted = sound.muted }.padding(6.dp))
        }
        Box(Modifier.fillMaxSize()) {
        // He glides sideways (240 units) and hops while he talks, so the tail reads his *live* box
        // rather than a rest pose - otherwise the tip lands beside his mouth for most of the run.
        var mouthPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var bubPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var bubH by remember { mutableIntStateOf(0) }
        val avatar = 452.dp
        // Draw order: his stage and him, then the bubble, then the tail over the bubble's border.
        Box(Modifier.fillMaxSize().padding(start = 32.dp, end = 48.dp, top = 56.dp, bottom = 24.dp)) {
            Box(Modifier.fillMaxHeight().width(avatar), contentAlignment = Alignment.BottomStart) {
                HelperStage(avatar)
                // He waves hello and goodbye, and when he cheers.
                HelperAvatar(talking = !typed, mood = mood, hops = hops,
                    waving = step == 0 || step == steps.lastIndex || mood == Mood.HAPPY, width = avatar,
                    dance = dance, onTaDa = { sound.play("success") },
                    onMouth = { mouthPos = it })
            }
            Column(Modifier.padding(start = avatar + 24.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                SpeechBubble(said, full, onLetter = { sound.blip() }, onTyped = { typed = true },
                    modifier = Modifier.onGloballyPositioned { bubPos = it.positionInRoot(); bubH = it.size.height }
                        .focusRequester(bubbleFocus).clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null) { advance() })     // no focus outline: it wrapped the tail too, and "Ⓐ continue" says it
                // Sits between the bubble and the hint, hard right: the bubble says what is being asked,
                // the check says whether it is already handled, and the hint says what A does next.
                    if (atCard) cur.done?.let { ok ->
                        Text(if (ok) "✅  Done" else "⏳  Not yet", color = if (ok) Hand.Good else Hand.Warn,
                            fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp))
                    }
                // Always visible: A does something useful in every state, and this is the only thing that says so.
                    Text("Ⓐ  " + if (atCard && cur.next != "Next") cur.next else "continue", color = Hand.Muted,
                        fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                AnimatedVisibility(atCard, enter = fadeIn() + slideInVertically { it / 3 }) {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        cur.extra?.invoke()
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val pill = Modifier.height(54.dp).glowFocus(RoundedCornerShape(50))
                            if (needs) Button(onClick = { sound.play("select"); cur.act() }, pill.focusRequester(cardFocus)) {
                                Text(cur.action!!, fontSize = 18.sp)
                            }
                        }
                        // The way round a switch that Android greys out, so no step can dead-end.
                        cur.alt?.let { alt ->
                            Text(alt, color = Hand.Blue, fontSize = 16.sp, modifier = Modifier
                                .glowFocus(RoundedCornerShape(8.dp))
                                .clickable { sound.play("select"); cur.altAct() }.padding(vertical = 4.dp, horizontal = 2.dp))
                        }
                    }
                }
            }
            // Last, so its fill covers the bubble's border where they join: one shape, not two.
            if (bubH > 0) SpeechTail(bubbleTopLeft = bubPos, bubbleHeight = bubH.toFloat(), mouth = mouthPos,
                modifier = Modifier.fillMaxSize())
        }
}
    }
}

/**
 * The last step's list of what is actually on, in HandyHelper's plain words, so the owner doesn't have to
 * walk back through Settings to check. Read from the same [Status] the steps were built from, so it can
 * only ever agree with them.
 */
@Composable
private fun SetupSummary(s: SetupStatus) {
    data class Line(val what: String, val on: Boolean)
    // One column, short labels, three rows. The mascot owns the left half of the screen, so the card
    // is only ~720 px wide: anything wider wrapped to two lines each and pushed the "Start using
    // HandyTuner" button past y=1080, leaving the run impossible to finish.
    val lines = listOf(
        Line("Overlay and hotkeys", s.a11y),
        Line("Usage access", s.usage),
        Line("Notifications", s.notif),
    )
    Column(Modifier.background(Color(0xE0050510), RoundedCornerShape(16.dp))
        .border(3.dp, Hand.Blue.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { (what, on) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A hollow box, not an alarm: a missing optional thing isn't a failure.
                Text(if (on) "✅" else "☐", fontSize = 15.sp)
                Text(what, color = if (on) Hand.Good else Hand.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** The six accent swatches and a tiny live HUD in the chosen glow. */
@Composable
private fun AccentPicker(style: HudStyle, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        HudStyle.ACCENTS.forEach { (_, argb) ->
            Box(Modifier.size(48.dp).glowFocus(CircleShape).background(Color(argb), CircleShape)
                .border(4.dp, if (style.accent == argb) Color.White else Color.Transparent, CircleShape)
                .clickable { onPick(argb) })
        }
    }
    val c = Color(style.accent)
    Column(Modifier.background(Color(0xE0050510), RoundedCornerShape(16.dp)).border(3.dp, c, RoundedCornerShape(16.dp))
        .padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text("FPS 60   GAME 42ms   NET 24ms   5G ▂▄▆█", fontFamily = Hand.Mono, color = Hand.Good, fontSize = 17.sp)
        Text("RAM 1.9G   12m −3%   17:05", fontFamily = Hand.Mono, color = c, fontSize = 17.sp)
    }
}

const val COCOON = "rip.moth.cocoonshell"
