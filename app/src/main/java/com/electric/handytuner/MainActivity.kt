// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.activity.compose.setContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object { const val PAGE = "page" }   // which side-menu page to open on
    // Hotkey recorder: while on, every button press is captured here (and kept from the UI),
    // and the biggest set held at once becomes the HUD hotkey.
    private var recording by mutableStateOf(false)
    private var hotkeyLabel by mutableStateOf("")
    private var menuKeyLabel by mutableStateOf("")
    private var recordingFor: Hotkey? = null
    private val recHeld = mutableSetOf<Int>()
    private var recBest = emptySet<Int>()

    // Onboarding: shown until finished once; the hotkey step watches for the HUD combo here.
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private var onboarded by mutableStateOf(true)
    /** What's New is showing (once after each update). */
    private var whatsNew by mutableStateOf(false)
    private var hotkeyHeld by mutableStateOf(false)
    private var menuHeld by mutableStateOf(false)
    private val appHeld = mutableSetOf<Int>()

    /** The Controller page's button test and "press a button" capture. Returning true keeps the key from the UI. */
    var keyHook: ((android.view.KeyEvent) -> Boolean)? = null
    /** The Controller page's stick test. */
    var motionHook: ((android.view.MotionEvent) -> Unit)? = null
    /** L1/R1 pressed on their own: the shell moves one section (-1/+1). */
    var onShoulder: ((Int) -> Unit)? = null
    private val shoulder = Nav.Shoulder()

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        motionHook?.invoke(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!recording && keyHook?.invoke(event) == true) return true
        if (!recording) {
            when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    appHeld += event.keyCode
                    if (appHeld.containsAll(Hotkey.HUD.load(this))) hotkeyHeld = true
                    if (appHeld.containsAll(Hotkey.MENU.load(this))) menuHeld = true
                }
                android.view.KeyEvent.ACTION_UP -> appHeld -= event.keyCode
            }
            // Not while a page listens to raw buttons (the button test): L1 there is a button to test.
            if (keyHook == null && event.repeatCount == 0) when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> shoulder.down(event.keyCode, appHeld.any { it != event.keyCode })
                android.view.KeyEvent.ACTION_UP -> shoulder.up(event.keyCode).takeIf { it != 0 }?.let { d ->
                    onShoulder?.let { it(d); return true }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (!Pads.isRecordable(event.keyCode, event.flags)) return true   // stick movement
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> { recHeld += event.keyCode; if (recHeld.size > recBest.size) recBest = recHeld.toSet() }
            // A combo of two or more, all let go: done, no need to wait out the timer.
            android.view.KeyEvent.ACTION_UP -> { recHeld -= event.keyCode; if (recHeld.isEmpty() && recBest.size >= 2) finishHotkey() }
        }
        return true
    }

    /**
     * A button pressed here but let go in another app (B that leaves HandyTuner) never sends its "up" here, so it
     * stayed in [appHeld] for good: every L1/R1 then counted as part of a combo and stopped switching sections.
     * Whatever was held is gone once the window loses focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) appHeld.clear()
    }

    private val hotkeyTimeout = Runnable { finishHotkey() }

    private fun recordHotkey(which: Hotkey) {
        recording = true; recordingFor = which; recHeld.clear(); recBest = emptySet()
        window.decorView.postDelayed(hotkeyTimeout, 10_000)   // nothing (or one button) pressed: give up
    }

    private fun finishHotkey() {
        val which = recordingFor ?: return
        window.decorView.removeCallbacks(hotkeyTimeout)
        recording = false; recordingFor = null
        if (recBest.size >= 2) which.save(this, recBest)
        android.util.Log.i("HandyTuner", "hotkey recorded $recBest = ${Hotkey.names(recBest)}")
        loadLabels()
    }

    private fun loadLabels() {
        hotkeyLabel = Hotkey.pretty(Hotkey.HUD.load(this))
        menuKeyLabel = Hotkey.pretty(Hotkey.MENU.load(this))
    }

    internal val actions by lazy { Actions(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLabels()
        Hand.light = HudStyle.load(this).light
        onboarded = prefs.getBoolean("onboarded", false)
        whatsNew = WhatsNew.due(this, onboarded)
        setContent {
            HandyTheme {
                if (!onboarded) {
                    Onboarding(this@MainActivity, resumes, hotkeyHeld, menuHeld,
                        askNotifications = { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }) { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }
                    return@HandyTheme
                }
                if (whatsNew) {
                    fun close() { WhatsNew.markSeen(this@MainActivity); whatsNew = false }
                    BackHandler { close() }
                    Box(Modifier.fillMaxSize().background(Hand.Bg).padding(horizontal = 48.dp, vertical = 20.dp)) { WhatsNewPage(this@MainActivity) { close() } }
                    return@HandyTheme
                }
                Shell()
            }
        }
    }

    /**
     * The console shell: a section list on the left, the section's rows on the right, button hints along the bottom.
     * Moving through the list shows each section straight away (as Steam's settings do); A or ▶ goes into it,
     * B comes back out, L1/R1 jump between sections from anywhere.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Shell() {
        var section by remember { mutableStateOf(Section.from(intent.getStringExtra(PAGE))) }
        var sub by remember { mutableStateOf<Sub?>(null) }
        val returnTo = remember { ReturnTo() }
        var contentFocused by remember { mutableStateOf(false) }
        val hints = remember { Hints() }
        val items = remember { Section.entries.associateWith { FocusRequester() } }
        val content = remember { FocusRequester() }
        val subEntry = remember { FocusRequester() }
        var subFocused by remember { mutableStateOf(false) }
        fun go(s: Section) { if (s != section) { section = s; sub = null } }

        DisposableEffect(Unit) {
            onShoulder = { d ->
                val next = Section.entries[Nav.step(section.ordinal, d, Section.entries.size)]
                go(next); runCatching { items.getValue(next).requestFocus() }
            }
            onDispose { onShoulder = null }
        }
        // Start with the D-pad on the current section, so the first press does something. The window isn't
        // focused on the first frames (and the request is dropped), so take view focus and retry until it holds.
        var listFocused by remember { mutableStateOf(false) }
        val view = androidx.compose.ui.platform.LocalView.current
        // Android starts apps in touch mode, where Compose won't focus a clickable until a button is pressed.
        // This app is driven by the Odin's controller, so start in keyboard (D-pad) mode.
        val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(Unit) {
            repeat(15) {
                if (listFocused || contentFocused) return@LaunchedEffect
                delay(100)
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                if (!view.hasFocus()) view.requestFocus()
                runCatching { items.getValue(section).requestFocus() }
            }
        }
        // A drill-in opened: focus its first row. The pages cross-fade, so for a moment the old page (and its focused
        // row) is still there: aim at the new screen itself, and retry until focus is really inside it.
        // (Closing is the other way round: the row that opened it takes focus back, see [ReturnTo].)
        LaunchedEffect(sub) {
            if (sub == null) return@LaunchedEffect
            repeat(15) { delay(40); if (subFocused) return@LaunchedEffect; runCatching { subEntry.requestFocus() } }
        }
        // Safety net: if the focused row goes away (a game removed, a layout deleted), the D-pad would have nothing
        // to move from. Put focus back in the page, else on the section. Only once the app has had focus at all, so
        // it never fights the start-up focus above.
        var shellFocused by remember { mutableStateOf(false) }
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(shellFocused, everFocused) {
            if (shellFocused || !everFocused) return@LaunchedEffect
            delay(120)
            if (!shellFocused) runCatching { content.requestFocus() }
            delay(80)
            if (!shellFocused) runCatching { items.getValue(section).requestFocus() }
        }

        BackHandler(enabled = sub != null || contentFocused) {
            val s = sub
            if (s != null) { returnTo.name = s.from; sub = null } else runCatching { items.getValue(section).requestFocus() }
        }

        val grad = Hand.Grad; val bg = Hand.Bg
        CompositionLocalProvider(
            LocalHints provides hints, LocalReturnTo provides returnTo, LocalOpenSub provides { s: Sub -> sub = s },
            LocalCloseSub provides { sub?.let { returnTo.name = it.from }; sub = null },
            LocalGoTo provides { s: Section -> go(s); runCatching { items.getValue(s).requestFocus() } },
        ) {
            Column(Modifier.fillMaxSize().onFocusChanged { shellFocused = it.hasFocus; if (it.hasFocus) everFocused = true }.drawBehind {
                // A soft glow from the bottom right; the top stays the page color, so it meets the status bar cleanly.
                drawRect(Brush.radialGradient(listOf(grad, bg), center = Offset(size.width, size.height), radius = size.maxDimension * 0.85f))
            }) {
                Row(Modifier.weight(1f)) {
                    SectionList(section, items, onFocus = { listFocused = true; go(it) }, onEnter = { runCatching { content.requestFocus() } })
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 28.dp, top = 16.dp)) {
                        val s = sub
                        when {
                            s != null -> Row(Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                                Text(section.label, color = Hand.Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("  ›  ", color = Hand.Muted, fontSize = 20.sp)
                                Text(s.title, color = Hand.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            section != Section.HOME -> SectionHeader(section.icon, section.label, section.about)
                        }
                        val fade = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
                        Box(Modifier.weight(1f).fillMaxWidth()
                            // Rows fade out under the title instead of being sliced off at the edge.
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(Brush.verticalGradient(0f to Color.Transparent, (fade / size.height).coerceIn(0f, 1f) to Color.Black),
                                    blendMode = BlendMode.DstIn)
                            }
                            .focusRequester(content).onFocusChanged { contentFocused = it.hasFocus }
                            .focusGroup()) {
                            AnimatedContent(section to sub, label = "section", transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }) togetherWith fadeOut(tween(120))
                            }) { (sec, sb) ->
                                if (sb != null) Box(Modifier.fillMaxSize().focusRequester(subEntry).onFocusChanged { subFocused = it.hasFocus }.focusGroup()) { sb.content() }
                                else SectionPage(sec)
                            }
                        }
                    }
                }
                HintBar(hints.kind, sub != null)
            }
        }
    }

    @Composable
    private fun SectionPage(s: Section) = when (s) {
        Section.HOME -> HomePage(this, actions, hotkeyLabel, menuKeyLabel)
        Section.GAMES -> GamesPage(this)
        Section.PERFORMANCE -> PerformancePage(this)
        Section.HUD -> HudSettings(this) { HotkeyRows() }
        Section.BATTERY -> BatteryPage(this)
        Section.NETWORK -> NetworkPage(this)
        Section.DOCK -> DockPage(this)
        Section.CONTROLLER -> ControllerPage(this)
        Section.SYSTEM -> SystemPage(this)
    }

    /** The section list: brand on top, one row per section. Entering it from the right lands on the current section. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun SectionList(current: Section, items: Map<Section, FocusRequester>, onFocus: (Section) -> Unit, onEnter: () -> Unit) {
        val edge = Hand.Blue.copy(alpha = 0.16f)
        // Always scrollable, never toggled: changing the scroll modifier around a focused row broke focus in 0.11.3.
        Column(
            Modifier.width(236.dp).fillMaxHeight().background(Hand.Card.copy(alpha = if (Hand.light) 0.7f else 0.55f))
                .drawBehind { drawLine(edge, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
                .focusProperties {
                    // D-pad into the list lands on the current section. A jump made in code (L1/R1, B, a Home tile) asks
                    // for its own target: redirecting that too sent R1 from a page straight back to where it started.
                    enter = { dir -> if (dir == FocusDirection.Enter) FocusRequester.Default else items.getValue(current) }
                }.focusGroup()
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Section.entries.forEach { s -> SectionItem(s, s == current, items.getValue(s), { onFocus(s) }, onEnter) }
        }
    }

    @Composable
    private fun SectionItem(s: Section, selected: Boolean, req: FocusRequester, onFocus: () -> Unit, onEnter: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val hints = LocalHints.current
        val shape = RoundedCornerShape(14.dp)
        val bg by animateColorAsState(when { focused -> Hand.Blue; selected -> Hand.Blue.copy(alpha = 0.16f); else -> Color.Transparent }, label = "item")
        val glow = focused && !Hand.light; val glowColor = Hand.Blue; val bar = Hand.Blue
        Row(
            Modifier.fillMaxWidth().height(38.dp)
                .drawBehind {
                    if (glow) listOf(8.dp to 0.08f, 4.dp to 0.16f).forEach { (grow, a) ->
                        val g = grow.toPx()
                        drawRoundRect(glowColor.copy(alpha = a), Offset(-g, -g), androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g),
                            androidx.compose.ui.geometry.CornerRadius(14.dp.toPx() + g))
                    }
                    // The current section keeps a bar on its left edge while you're in its rows.
                    if (selected && !focused) drawRoundRect(bar, Offset(0f, size.height * 0.22f), androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height * 0.56f),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
                .background(bg, shape)
                .focusRequester(req)
                .onFocusChanged { f -> if (f.isFocused != focused) { focused = f.isFocused; if (focused) { hints.kind = RowKind.LIST; onFocus() } } }
                // A or ▶ goes into the section; a tap only picks it (no focus bar jumping into the rows under a finger).
                .onPreviewKeyEvent { e ->
                    val into = e.key == Key.DirectionRight || e.key == Key.ButtonA || e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter
                    if (into && e.type == KeyEventType.KeyDown) onEnter()
                    into
                }
                .clip(shape).clickable { onFocus() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(s.icon, null, tint = when { focused -> Color.White; selected -> Hand.Blue; else -> Hand.Muted }, modifier = Modifier.size(22.dp))
            Text(s.label, color = if (focused) Color.White else if (selected) Hand.Text else Hand.Muted, fontSize = 16.sp,
                fontWeight = if (selected || focused) FontWeight.ExtraBold else FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
        }
    }

    /** HUD › Hotkeys: A, then hold the new combo and let go. While recording, every button is captured here. */
    @Composable
    private fun HotkeyRows() {
        listOf(Triple("HUD hotkey", Hotkey.HUD, hotkeyLabel), Triple("Quick Menu hotkey", Hotkey.MENU, menuKeyLabel)).forEach { (name, key, label) ->
            val now = recording && recordingFor == key
            ActionRow(name, hint = if (now) "Hold your buttons, then let go…" else "Press Ⓐ, then hold the new combo",
                value = label, icon = Icons.Rounded.Keyboard) { if (!recording) recordHotkey(key) }
        }
    }

    /** Bumped on every return to the app, so a permission granted in Settings shows at once. */
    internal var resumes by mutableStateOf(0)
        private set

    /** System › Run setup again: the first-start walkthrough, from the top. */
    internal fun runSetupAgain() { prefs.edit().putBoolean("onboarded", false).apply(); onboarded = false }
    override fun onResume() { super.onResume(); resumes++ }
}

/** The app's sections, in list order. [about] is the one line under the title. */
enum class Section(val label: String, val icon: ImageVector, val about: String?) {
    HOME("Home", Icons.Rounded.Home, null),
    GAMES("Games", Icons.Rounded.SportsEsports, "Each game gets its preset when it opens"),
    PERFORMANCE("Performance", Icons.Rounded.Bolt, "The PULSE engine, and what runs in the background"),
    HUD("HUD", Icons.Rounded.Insights, "The stats box over your games"),
    BATTERY("Battery", Icons.Rounded.BatteryChargingFull, "Charging and battery life"),
    NETWORK("Network", Icons.Rounded.Wifi, "Ping, Wi-Fi and DNS"),
    DOCK("Dock & Display", Icons.Rounded.Tv, "TV, monitor, charger and screen resolution"),
    CONTROLLER("Controller", Icons.Rounded.Gamepad, "Buttons, gamepads and key mapping"),
    SYSTEM("System", Icons.Rounded.Settings, "Theme, diagnostics and about");

    companion object {
        /** From [MainActivity.PAGE]; the pre-redesign page names still land somewhere sensible. */
        fun from(name: String?): Section = entries.firstOrNull { it.name == name } ?: when (name) {
            "TWEAKS" -> PERFORMANCE
            "DIAGNOSTICS" -> SYSTEM
            else -> HOME
        }
    }
}

