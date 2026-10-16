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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
        hotkeyLabel = Hotkey.names(Hotkey.HUD.load(this))
        menuKeyLabel = Hotkey.names(Hotkey.MENU.load(this))
    }

    private val actions by lazy { Actions(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLabels()
        Hand.light = HudStyle.load(this).light
        onboarded = prefs.getBoolean("onboarded", false)
        setContent {
            HandyTheme {
                if (!onboarded) {
                    Onboarding(this@MainActivity, resumes, hotkeyHeld, menuHeld,
                        askNotifications = { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }) { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }
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
        BackHandler(enabled = sub != null || contentFocused) {
            val s = sub
            if (s != null) { returnTo.name = s.from; sub = null } else runCatching { items.getValue(section).requestFocus() }
        }

        val grad = Hand.Grad; val bg = Hand.Bg
        CompositionLocalProvider(LocalHints provides hints, LocalReturnTo provides returnTo, LocalOpenSub provides { s: Sub -> sub = s }) {
            Column(Modifier.fillMaxSize().drawBehind {
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
                        Box(Modifier.weight(1f).fillMaxWidth().focusRequester(content).onFocusChanged { contentFocused = it.hasFocus }
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
        Section.HOME -> HomePage(this, actions)
        Section.GAMES -> GamesPage(this)
        Section.PERFORMANCE -> PerformancePage(this)
        Section.HUD -> HudSettings(this) { HotkeyCard() }
        Section.BATTERY -> BatteryPage(this)
        Section.NETWORK -> NetworkPage(this)
        Section.DOCK -> DockPage(this)
        Section.CONTROLLER -> ControllerPage(this)
        Section.SYSTEM -> Column {
            // Stage 1 of the redesign: the old Tweaks and Diagnostics pages, until System gets its rows.
            var tab by remember { mutableStateOf(0) }
            SubTabs(listOf("Tweaks", "Diagnostics"), tab) { tab = it }
            if (tab == 0) TweaksPage(this@MainActivity) else DiagnosticsPage()
        }
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
                .focusProperties { enter = { items.getValue(current) } }.focusGroup()
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

    @Composable
    private fun HotkeyCard() = HandCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        listOf(Triple("HUD hotkey", Hotkey.HUD, hotkeyLabel), Triple("Quick Menu hotkey", Hotkey.MENU, menuKeyLabel)).forEach { (name, key, label) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (!recording) recordHotkey(key) }, modifier = Modifier.glowFocus(RoundedCornerShape(50)).width(260.dp)) {
                    Text(if (recording && recordingFor == key) "Hold your buttons, then let go…" else "Record")
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(name, color = Hand.Muted, fontSize = 13.sp)
                    Text(label.replace("BUTTON_", ""), fontFamily = Hand.Mono, color = Hand.Text)
                }
            }
        }
    }

    /** Bumped on every return to the app, so a permission granted in Settings shows at once. */
    private var resumes by mutableStateOf(0)
    override fun onResume() { super.onResume(); resumes++ }

    @Composable
    private fun DiagnosticsPage() {
        var result by remember { mutableStateOf<Pair<List<Diagnostics.Check>, List<Diagnostics.Check>>?>(null) }
        var logNote by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        LaunchedEffect(resumes) {
            result = withContext(Dispatchers.IO) {
                Pair(Diagnostics.setup(this@MainActivity), Diagnostics.apps(this@MainActivity))
            }   // One line each in logcat, so a report can be read off the device without screenshots.
                .also { (a, b) -> (a + b).forEach { android.util.Log.i("HandyTuner", "diag ${it.status} ${it.name}: ${it.detail}") } }
        }
        val r = result
        if (r == null) { Text("Checking…", color = Hand.Muted); return }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Permissions & access", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp) }
            items(r.first) { CheckRow(it) }
            item { Text("Apps HandyTuner works with", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
                modifier = Modifier.padding(top = 12.dp)) }
            items(r.second) { CheckRow(it) }
            item {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { prefs.edit().putBoolean("onboarded", false).apply(); onboarded = false },
                        Modifier.glowFocus(RoundedCornerShape(50))) { Text("Run setup again") }
                    Button(onClick = {
                        logNote = "Saving…"
                        scope.launch(Dispatchers.IO) {
                            val name = Diagnostics.exportLog(this@MainActivity)
                            logNote = if (name != null) "Saved to Downloads: $name" else "Couldn't save the log file."
                        }
                    }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Export log file") }
                }
                logNote?.let { Text(it, color = Hand.Blue, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            item { ResetCard() }
            item { SafeModeCard() }
        }
    }

    /**
     * Safe mode, and the only way out of it.
     *
     * Deliberately not in the overlay: clearing this says "the restart loop was a one-off, start
     * writing to my device again", which is the owner's judgement to make with the app open, not
     * something a game menu should be able to undo on its own.
     */
    @Composable
    private fun SafeModeCard() {
        var on by remember { mutableStateOf(SafeMode.active(this@MainActivity)) }
        if (!on) return
        var when0 by remember { mutableStateOf(SafeMode.since(this@MainActivity)) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        HandCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Safe mode", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text("On since ${when0?.let { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "unknown"} — ${SafeMode.why(this@MainActivity)}. HandyTuner isn't changing anything until you clear this. What it changed before is still on the Odin; Reset everything to stock below puts that back.",
                color = Hand.Muted, fontSize = 14.sp)
            Button(onClick = {
                SafeMode.set(this@MainActivity, false)
                on = false
                android.util.Log.i("HandyTuner", "safe mode cleared by the owner")
            }, Modifier.padding(top = 8.dp).glowFocus(RoundedCornerShape(50))) { Text("Clear safe mode") }
        }
    }

    /** Puts back every setting HandyTuner changed. Hotkeys and the HUD's look are HandyTuner's own, so they stay. */
    @Composable
    private fun ResetCard() {
        var confirm by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf<Int?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        HandCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Reset everything to stock", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(done?.let { "Done: $it setting${if (it == 1) "" else "s"} put back." }
                ?: "Puts back every setting HandyTuner changed: brightness, refresh rate, scanning, Private DNS, button layout, stick lights, sleep underclock, frame caps, resolution, the Odin swipe-in menu.",
                color = Hand.Muted, fontSize = 14.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!confirm) Button(onClick = { confirm = true }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Reset…") }
                else {
                    Button(onClick = {
                        confirm = false
                        scope.launch(Dispatchers.IO) {
                            val n = Originals.saved(this@MainActivity).size
                            Originals.restore(this@MainActivity)
                            if (java.io.File(filesDir, "gms_off").exists()) actions.setPlayServices(true)
                            if (java.io.File(filesDir, "odin_menu_off").exists()) actions.setOdinMenu(true)
                            Resolution.save(this@MainActivity, Resolution()); Resolution.putBack(this@MainActivity)
                            java.io.File(filesDir, "reset").writeText(System.currentTimeMillis().toString())
                            android.util.Log.i("HandyTuner", "reset to stock: $n settings")
                            withContext(Dispatchers.Main) { done = n }
                        }
                    }, Modifier.glowFocus(RoundedCornerShape(50)),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Hand.Bad)) { Text("Yes, reset") }
                    Button(onClick = { confirm = false }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Cancel") }
                }
            }
        }
    }

    @Composable
    private fun CheckRow(c: Diagnostics.Check) = HandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.status.mark, fontSize = 20.sp, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(c.detail, color = Hand.Muted, fontSize = 14.sp)
            }
            if (c.status == Diagnostics.Status.GRANT && c.fix != null) {
                Button(onClick = { startActivity(c.fix) }, Modifier.glowFocus(RoundedCornerShape(50))) {
                    Text(if (c.fix.action == Intent.ACTION_VIEW) "Get" else "Fix")
                }
            }
        }
    }
}

/** The app's sections, in list order. [about] is the one line under the title. */
enum class Section(val label: String, val icon: ImageVector, val about: String?) {
    HOME("Home", Icons.Rounded.Home, null),
    GAMES("Games", Icons.Rounded.SportsEsports, "Each game gets its preset when it opens"),
    PERFORMANCE("Performance", Icons.Rounded.Bolt, "How the PULSE engine runs, in every game"),
    HUD("HUD", Icons.Rounded.Insights, "The stats box over your games"),
    BATTERY("Battery", Icons.Rounded.BatteryChargingFull, "Charging and battery life"),
    NETWORK("Network", Icons.Rounded.Wifi, "Ping, Wi-Fi and DNS"),
    DOCK("Dock", Icons.Rounded.Tv, "TV, monitor and charger setups"),
    CONTROLLER("Controller", Icons.Rounded.Gamepad, "Buttons, gamepads and key mapping"),
    SYSTEM("System", Icons.Rounded.Settings, "Quick fixes, diagnostics and about");

    companion object {
        /** From [MainActivity.PAGE]; the pre-redesign page names still land somewhere sensible. */
        fun from(name: String?): Section = entries.firstOrNull { it.name == name } ?: when (name) {
            "TWEAKS" -> PERFORMANCE
            "DIAGNOSTICS" -> SYSTEM
            else -> HOME
        }
    }
}

