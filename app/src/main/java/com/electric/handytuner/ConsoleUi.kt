// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * The console-style building blocks (2026-10 redesign): every setting is one focusable row that lights up as a
 * blue bar, and nothing inside a row takes focus, so the D-pad only ever stops on whole rows. Rows sit in
 * rounded groups under small headers; a row with › opens a drill-in screen that B closes.
 */

/** What the focused row does with the buttons, for the hint bar at the bottom. */
enum class RowKind { LIST, SELECT, TOGGLE, CHOICE, SLIDER, OPEN, GAME, SCROLL }

/** The hint bar reads this; the focused row sets it. */
class Hints { var kind by mutableStateOf(RowKind.LIST) }
val LocalHints = staticCompositionLocalOf { Hints() }

/** A drill-in screen: [title] goes after the section name ("Battery › Play sessions"); [from] is the row that opened it. */
class Sub(val title: String, val from: String? = null, val content: @Composable () -> Unit)
val LocalOpenSub = staticCompositionLocalOf<(Sub) -> Unit> { {} }

/**
 * Which row should take focus when it next appears: the [NavRow] a drill-in was opened from. By name, because
 * the page under a drill-in is rebuilt when you come back, so the row is a new one.
 */
class ReturnTo { var name by mutableStateOf<String?>(null) }
val LocalReturnTo = staticCompositionLocalOf { ReturnTo() }

/** Closes the open drill-in (for screens with their own Back button). */
val LocalCloseSub = staticCompositionLocalOf<() -> Unit> { {} }

/** Jumps to another section, with the D-pad on it in the section list (Home's tiles use it). */
val LocalGoTo = staticCompositionLocalOf<(Section) -> Unit> { {} }

private val RowShape = RoundedCornerShape(14.dp)
private val GroupShape = RoundedCornerShape(20.dp)

/** One page of rows: scrolls, and the D-pad scrolls it by moving between rows. */
@Composable
fun ConsolePage(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), content = content)

/** A small caps header and a rounded card holding its rows. */
@Composable
fun RowGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.padding(top = 18.dp)) {
    title?.let {
        Text(it.uppercase(), color = Hand.Muted, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
    }
    Column(
        Modifier.fillMaxWidth().background(Hand.Card, GroupShape).border(1.dp, Hand.Blue.copy(alpha = 0.18f), GroupShape).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** A line of text between groups: a warning, or what a page is waiting for. */
@Composable
fun Note(text: String, color: Color = Hand.Muted) =
    Text(text, color = color, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))

/**
 * The row everything else is built on. [hint] is always one line; [help] shows only while the row is focused.
 * A disabled row still takes focus (so the D-pad can scroll past it) but does nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleRow(
    name: String,
    hint: String? = null,
    help: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    kind: RowKind = RowKind.SELECT,
    danger: Boolean = false,
    nameColor: Color? = null,
    hintColor: Color? = null,
    requester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (focused: Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val hints = LocalHints.current
    val bg by animateColorAsState(if (focused) Hand.Blue else Color.Transparent, label = "row")
    // Scrolling to a focused row keeps room above it, so its group's header stays on screen.
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val glow = focused && !Hand.light
    val glowColor = Hand.Blue
    Column(
        Modifier.fillMaxWidth()
            // Dark only: a soft blue halo. Elevation shadows vanish on black, so it's drawn.
            .drawBehind {
                if (!glow) return@drawBehind
                listOf(10.dp to 0.07f, 6.dp to 0.12f, 3.dp to 0.2f).forEach { (grow, a) ->
                    val g = grow.toPx()
                    drawRoundRect(glowColor.copy(alpha = a), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(14.dp.toPx() + g))
                }
            }
            .background(bg, RowShape)
            .onSizeChanged { size = it }
            .bringIntoViewRequester(bring)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { f ->
                if (f.isFocused != focused) { focused = f.isFocused; onFocus(f.isFocused) }
                if (f.isFocused) {
                    hints.kind = kind
                    val above = with(density) { 56.dp.toPx() }; val below = with(density) { 24.dp.toPx() }
                    scope.launch { bring.bringIntoView(Rect(0f, -above, size.width.toFloat(), size.height + below)) }
                }
            }
            .onPreviewKeyEvent { e -> enabled && onKey?.invoke(e) == true }
            .clip(RowShape)
            .clickable { if (enabled) onClick?.invoke() }
            .padding(horizontal = 18.dp, vertical = 11.dp)
            .heightIn(min = 34.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (focused) Color.White else nameColor ?: if (danger) Hand.Bad else Hand.Text
        val sub = if (focused) Color.White.copy(alpha = 0.85f) else hintColor ?: Hand.Muted
        Row(Modifier.alpha(if (enabled) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, null, tint = if (focused) Color.White else Hand.Blue, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hint?.let { Text(it, color = sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box(Modifier.padding(start = 12.dp)) { trailing(focused) }
        }
        if (focused && help != null) Text(help, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp, end = 8.dp))
    }
}

/** True for a key-down of [k]; key-ups pass through so nothing else sees half a press. */
private fun KeyEvent.down(k: Key) = key == k && type == KeyEventType.KeyDown
/** ◀ or ▶, up or down: a row that uses them keeps both halves of the press from moving focus. */
private fun KeyEvent.isLeftRight() = key == Key.DirectionLeft || key == Key.DirectionRight

/** A toggle: A flips it. With [confirmOff], turning it off takes a second A (the first explains). */
@Composable
fun ToggleRow(
    name: String, checked: Boolean, hint: String? = null, help: String? = null, icon: ImageVector? = null,
    enabled: Boolean = true, confirmOff: String? = null, onChange: (Boolean) -> Unit,
) {
    var arming by remember { mutableStateOf(false) }
    ConsoleRow(
        name, if (arming) "Press Ⓐ again to turn it off" else hint, if (arming) confirmOff else help, icon, enabled, RowKind.TOGGLE,
        hintColor = if (arming) Hand.Warn else null,
        onFocus = { if (!it) arming = false },
        onClick = {
            when {
                checked && confirmOff != null && !arming -> arming = true
                else -> { arming = false; onChange(!checked) }
            }
        },
    ) { focused -> PillSwitch(checked, focused) }
}

/** The switch look, drawn: a Material Switch would take focus inside the row. */
@Composable
fun PillSwitch(on: Boolean, focused: Boolean) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, label = "thumb")
    val track = when {
        focused && on -> Color.White
        focused -> Color.White.copy(alpha = 0.35f)
        on -> Hand.Blue
        else -> Hand.Track
    }
    Box(Modifier.size(width = 48.dp, height = 28.dp).background(track, CircleShape).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(22.dp).background(if (focused && on) Hand.Blue else Color.White, CircleShape))
    }
}

/**
 * Pick one of [options]: ◀▶ step (stopping at the ends, so holding one never wraps you round), A steps forward and
 * wraps. [selected] -1 shows "–". [dot] gives an option a color swatch.
 */
@Composable
fun <T> ChoiceRow(
    name: String, options: List<T>, selected: Int, label: (T) -> String, hint: String? = null, help: String? = null,
    icon: ImageVector? = null, enabled: Boolean = true, dot: ((T) -> Color)? = null, kind: RowKind = RowKind.CHOICE,
    hintColor: Color? = null, onFocus: (Boolean) -> Unit = {}, onKey: ((KeyEvent) -> Boolean)? = null, onPick: (Int) -> Unit,
) {
    fun step(d: Int) { val n = Nav.step(selected, d, options.size); if (n != selected) onPick(n) }
    ConsoleRow(
        name, hint, help, icon, enabled, kind, hintColor = hintColor, onFocus = onFocus,
        onKey = { e ->
            when {
                onKey?.invoke(e) == true -> true
                e.down(Key.DirectionLeft) -> { step(-1); true }
                e.down(Key.DirectionRight) -> { step(1); true }
                e.isLeftRight() -> true
                else -> false
            }
        },
        onClick = { if (options.isNotEmpty()) onPick(Nav.next(selected, options.size)) },
    ) { focused ->
        val tint = if (focused) Color.White else Hand.Blue
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chevron(Icons.Rounded.ChevronLeft, tint, selected > 0) { if (enabled) step(-1) }
            val value = options.getOrNull(selected)
            if (value != null && dot != null) {
                Box(Modifier.size(16.dp).background(dot(value), CircleShape).border(1.5.dp, if (focused) Color.White else Hand.Muted.copy(alpha = 0.5f), CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value?.let(label) ?: "–", color = if (focused) Color.White else Hand.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.widthIn(min = 40.dp))
            Chevron(Icons.Rounded.ChevronRight, tint, selected < options.lastIndex) { if (enabled) step(1) }
        }
    }
}

/** A ◀/▶ arrow: dimmed at the end of the list, tappable, never focusable. */
@Composable
private fun Chevron(icon: ImageVector, tint: Color, live: Boolean, onTap: () -> Unit) =
    Icon(icon, null, tint = tint.copy(alpha = if (live) 1f else 0.25f),
        modifier = Modifier.size(30.dp).pointerInput(live) { detectTapGestures { if (live) onTap() } })

/**
 * A number: ◀▶ move it by [step], touch sets it on the bar. It's saved 250 ms after it stops moving, so
 * holding ◀ doesn't write every step (the Quick Menu slider does the same).
 */
@Composable
fun SliderRow(
    name: String, value: Int, range: IntRange, step: Int, format: (Int) -> String, hint: String? = null, help: String? = null,
    icon: ImageVector? = null, enabled: Boolean = true, onChange: (Int) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    LaunchedEffect(v) { if (v != value) { delay(250); onChange(v) } }
    fun snap(x: Int) = (range.first + Math.round((x - range.first) / step.toFloat()) * step).coerceIn(range.first, range.last)
    ConsoleRow(
        name, hint, help, icon, enabled, RowKind.SLIDER,
        onKey = { e ->
            when {
                e.down(Key.DirectionLeft) -> { v = snap(v - step); true }
                e.down(Key.DirectionRight) -> { v = snap(v + step); true }
                e.isLeftRight() -> true
                else -> false
            }
        },
    ) { focused ->
        var width by remember { mutableStateOf(1) }
        fun setFrom(px: Float) { if (enabled) v = snap(range.first + Math.round(px / width * (range.last - range.first))) }
        val frac = (v - range.first) / (range.last - range.first).toFloat().coerceAtLeast(1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(format(v), color = if (focused) Color.White else Hand.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 14.dp))
            Box(
                Modifier.width(170.dp).height(28.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
                    .pointerInput(enabled) { detectTapGestures { setFrom(it.x) } }
                    .pointerInput(enabled) { detectHorizontalDragGestures { c, _ -> setFrom(c.position.x) } },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(if (focused) Color.White.copy(alpha = 0.35f) else Hand.Bar, CircleShape))
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).background(if (focused) Color.White else Hand.Blue, CircleShape))
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(18.dp).background(Color.White, CircleShape).border(2.dp, Hand.Blue, CircleShape))
                }
            }
        }
    }
}

/**
 * A button: A runs it. [value] shows on the right (a result, or what it's set to). With [confirm], the first A
 * asks ("Press Ⓐ again to …") and leaving the row cancels.
 */
@Composable
fun ActionRow(
    name: String, hint: String? = null, help: String? = null, icon: ImageVector? = null, value: String? = null,
    enabled: Boolean = true, danger: Boolean = false, confirm: String? = null, onClick: () -> Unit,
) {
    var arming by remember { mutableStateOf(false) }
    ConsoleRow(
        name, if (arming) "Press Ⓐ again to $confirm" else hint, help, icon, enabled, RowKind.SELECT, danger,
        hintColor = if (arming) Hand.Warn else null,
        onFocus = { if (!it) arming = false },
        onClick = { if (confirm != null && !arming) arming = true else { arming = false; onClick() } },
    ) { focused -> value?.let { TrailingValue(it, focused) } }
}

/** A row with ›: opens a drill-in screen (or another app). When that screen closes, focus comes back here. */
@Composable
fun NavRow(name: String, hint: String? = null, icon: ImageVector? = null, value: String? = null, enabled: Boolean = true, onOpen: (String) -> Unit) {
    val me = remember { FocusRequester() }
    val ret = LocalReturnTo.current
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(ret.name) {
        if (ret.name != name) return@LaunchedEffect
        repeat(15) { delay(40); if (isFocused) { ret.name = null; return@LaunchedEffect }; runCatching { me.requestFocus() } }
        ret.name = null
    }
    ConsoleRow(name, hint, null, icon, enabled, RowKind.OPEN, requester = me, onFocus = { isFocused = it }, onClick = { onOpen(name) }) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            value?.let { TrailingValue(it, focused) }
            Icon(Icons.Rounded.ChevronRight, null, tint = if (focused) Color.White else Hand.Muted, modifier = Modifier.size(28.dp))
        }
    }
}

/** Opens [title] as a drill-in screen from the [NavRow] named `from`; B comes back to that row. */
@Composable
fun openSub(): (String, String, @Composable () -> Unit) -> Unit {
    val open = LocalOpenSub.current
    return { title, from, content -> open(Sub(title, from, content)) }
}

/** A read-only line. [focusable] ones let the D-pad scroll through a long list of them. */
@Composable
fun InfoRow(name: String, value: String, hint: String? = null, valueColor: Color? = null, focusable: Boolean = true) {
    if (focusable) ConsoleRow(name, hint, kind = RowKind.LIST) { focused -> TrailingValue(value, focused, valueColor) }
    else Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Hand.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            hint?.let { Text(it, color = Hand.Muted, fontSize = 13.sp) }
        }
        TrailingValue(value, false, valueColor)
    }
}

@Composable
private fun TrailingValue(text: String, focused: Boolean, color: Color? = null) =
    Text(text, color = if (focused) Color.White.copy(alpha = 0.9f) else color ?: Hand.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, modifier = Modifier.padding(end = 6.dp))

/** Custom content inside a group (a graph, test results): not focusable, padded like a row. */
@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), content = content)

/** The button glyphs in the hint bar, in the colors the buttons have on most pads. */
@Composable
fun ButtonGlyph(letter: String, color: Color) = Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
    Text(letter, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
}

@Composable
fun ShoulderGlyph(label: String) = Box(
    Modifier.height(22.dp).border(1.5.dp, Hand.Muted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
    contentAlignment = Alignment.Center,
) { Text(label, color = Hand.Text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }

/** Bottom bar: what the buttons do on the focused row. */
@Composable
fun HintBar(kind: RowKind, inSub: Boolean) {
    @Composable fun item(glyph: @Composable () -> Unit, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
        glyph(); Text(text, color = Hand.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp, end = 22.dp))
    }
    @Composable fun arrows() = Row {
        Icon(Icons.Rounded.ChevronLeft, null, tint = Hand.Text, modifier = Modifier.size(22.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Hand.Text, modifier = Modifier.size(22.dp))
    }
    val edge = Hand.Blue.copy(alpha = 0.18f)
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Hand.Card).drawBehind {
            drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.mascot), "HandyHelper", Modifier.width(34.dp))
        Text("Handy", color = Hand.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp))
        Text("Tuner", color = Hand.Blue, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 26.dp))
        item({ Row { ShoulderGlyph("L1"); Spacer(Modifier.width(4.dp)); ShoulderGlyph("R1") } }, "Section")
        Spacer(Modifier.weight(1f))
        when (kind) {
            RowKind.CHOICE -> item({ arrows() }, "Change")
            RowKind.SLIDER -> item({ arrows() }, "Adjust")
            RowKind.SCROLL -> item({ Text("▲▼", color = Hand.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }, "Scroll")
            RowKind.GAME -> { item({ arrows() }, "Preset"); item({ ButtonGlyph("Y", Hand.Warn) }, "Remove") }
            else -> {}
        }
        when (kind) {
            RowKind.LIST -> item({ ButtonGlyph("A", Hand.Good) }, "Open")
            RowKind.TOGGLE -> item({ ButtonGlyph("A", Hand.Good) }, "Toggle")
            RowKind.CHOICE, RowKind.GAME -> item({ ButtonGlyph("A", Hand.Good) }, "Next")
            RowKind.OPEN -> item({ ButtonGlyph("A", Hand.Good) }, "Open")
            RowKind.SLIDER, RowKind.SCROLL -> {}
            RowKind.SELECT -> item({ ButtonGlyph("A", Hand.Good) }, "Select")
        }
        item({ ButtonGlyph("B", Hand.Bad) }, if (kind == RowKind.LIST && !inSub) "Exit" else "Back")
    }
}

/** A section's title block: icon in a soft circle, title, one line about it. */
@Composable
fun SectionHeader(icon: ImageVector, title: String, about: String?) = Row(
    Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(52.dp).background(Hand.Blue.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Hand.Blue, modifier = Modifier.size(28.dp))
    }
    Column(Modifier.padding(start = 16.dp)) {
        Text(title, color = Hand.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        about?.let { Text(it, color = Hand.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A big focusable status tile (Home): an icon and title, a large value in [valueColor], and up to two lines.
 * A opens [onOpen] (the section it summarizes).
 */
@Composable
fun StatusTile(icon: ImageVector, title: String, value: String, valueColor: Color, lines: List<String>, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val hints = LocalHints.current
    val shape = RoundedCornerShape(20.dp)
    val bg by animateColorAsState(if (focused) Hand.Blue else Hand.Card, label = "tile")
    val glow = focused && !Hand.light; val glowColor = Hand.Blue; val edge = Hand.Blue.copy(alpha = 0.18f)
    Column(
        modifier
            .drawBehind {
                if (!glow) return@drawBehind
                listOf(10.dp to 0.07f, 6.dp to 0.12f, 3.dp to 0.2f).forEach { (grow, a) ->
                    val g = grow.toPx()
                    drawRoundRect(glowColor.copy(alpha = a), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(20.dp.toPx() + g))
                }
            }
            .background(bg, shape).border(1.dp, if (focused) Color.Transparent else edge, shape)
            .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) hints.kind = RowKind.OPEN }
            .clip(shape).clickable(onClick = onOpen)
            .padding(18.dp),
    ) {
        val fg = if (focused) Color.White else Hand.Text
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (focused) Color.White else Hand.Blue, modifier = Modifier.size(24.dp))
            Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        }
        Text(value, color = if (focused) Color.White else valueColor, fontFamily = Hand.Mono, fontSize = 30.sp, maxLines = 1,
            modifier = Modifier.padding(top = 10.dp))
        lines.forEach { Text(it, color = if (focused) Color.White.copy(alpha = 0.88f) else Hand.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

