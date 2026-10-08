// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> { recHeld += event.keyCode; if (recHeld.size > recBest.size) recBest = recHeld.toSet() }
            android.view.KeyEvent.ACTION_UP -> recHeld -= event.keyCode
        }
        return true
    }

    private fun recordHotkey(which: Hotkey) {
        recording = true; recordingFor = which; recHeld.clear(); recBest = emptySet()
        window.decorView.postDelayed({
            recording = false; recordingFor = null
            if (recBest.size >= 2) which.save(this, recBest)
            android.util.Log.i("HandyTuner", "hotkey recorded $recBest = ${Hotkey.names(recBest)}")
            loadLabels()
        }, 5_000)
    }

    private fun loadLabels() {
        hotkeyLabel = Hotkey.names(Hotkey.HUD.load(this))
        menuKeyLabel = Hotkey.names(Hotkey.MENU.load(this))
    }

    private val actions by lazy { Actions(this) }

    private enum class Page(val label: String, val icon: Int) {
        HOME("Home", R.drawable.ic_home), GAMES("Games", R.drawable.ic_sports_esports), HUD("HUD", R.drawable.ic_desktop_windows),
        BATTERY("Battery", R.drawable.ic_battery_horiz_075), NETWORK("Network", R.drawable.ic_wifi), TWEAKS("Tweaks", R.drawable.ic_tune), CONTROLLER("Controller", R.drawable.ic_sports_esports),
        DOCK("Dock & Screen", R.drawable.ic_desktop_windows),
        DIAGNOSTICS("Diagnostics", R.drawable.ic_build),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLabels()
        onboarded = prefs.getBoolean("onboarded", false)
        setContent {
            HandyTheme {
                if (!onboarded) {
                    Onboarding(this@MainActivity, resumes, hotkeyHeld, menuHeld,
                        askNotifications = { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }) { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }
                    return@HandyTheme
                }
                var page by remember { mutableStateOf(Page.entries.firstOrNull { it.name == intent.getStringExtra(PAGE) } ?: Page.HOME) }
                Row(Modifier.fillMaxSize().background(Color.Black)) {
                    // The board's left icon rail. Battery, Network and Controller join it as they're built.
                    Column(
                        // Scrolls, and items are compact: seven pages didn't fit a 1080p screen, and Diagnostics was cut off.
                        Modifier.fillMaxHeight().width(112.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Image(painterResource(R.drawable.mascot), "HandyHelper", Modifier.width(60.dp).padding(bottom = 2.dp))
                        Page.values().forEach { p -> RailItem(p.label, p.icon, page == p) { page = p } }
                    }
                    Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 20.dp, end = 24.dp)) {
                        // Explicit white: with no Surface around it, Text's default content color is black.
                        if (page != Page.HOME) Text(page.label, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                        when (page) {
                            Page.HOME -> HomePage(this@MainActivity, actions)
                            Page.GAMES -> GamesPage(this@MainActivity)
                            Page.HUD -> HudSettings(this@MainActivity) { HotkeyCard() }
                            Page.BATTERY -> BatteryPage(this@MainActivity)
                            Page.NETWORK -> NetworkPage(this@MainActivity)
                            Page.TWEAKS -> TweaksPage(this@MainActivity)
                            Page.CONTROLLER -> ControllerPage(this@MainActivity)
                            Page.DOCK -> DockPage(this@MainActivity)
                            Page.DIAGNOSTICS -> DiagnosticsPage()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RailItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.width(88.dp).glowFocus(shape)
                .background(if (selected) Hand.Blue.copy(alpha = 0.22f) else Color.Transparent, shape)
                .clickable(onClick = onClick).padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(icon), null, tint = if (selected) Hand.Blue else Hand.Muted, modifier = Modifier.size(24.dp))
            Text(label, fontSize = 11.sp, color = if (selected) Color.White else Hand.Muted)
        }
    }

    @Composable
    private fun HotkeyCard() = HandCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        listOf(Triple("HUD hotkey", Hotkey.HUD, hotkeyLabel), Triple("Quick Menu hotkey", Hotkey.MENU, menuKeyLabel)).forEach { (name, key, label) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (!recording) recordHotkey(key) }, modifier = Modifier.glowFocus(RoundedCornerShape(50)).width(260.dp)) {
                    Text(if (recording && recordingFor == key) "Hold your buttons now… (5 s)" else "Record")
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(name, color = Hand.Muted, fontSize = 13.sp)
                    Text(label.replace("BUTTON_", ""), fontFamily = Hand.Mono, color = Color.White)
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
            item { Text("Permissions & access", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp) }
            items(r.first) { CheckRow(it) }
            item { Text("Apps HandyTuner works with", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
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
            Text("Safe mode", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text("On since ${when0?.let { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "unknown"} — ${SafeMode.why(this@MainActivity)}. Everything HandyTuner changed was put back, and the overlay isn't changing anything until you clear this.",
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
            Text("Reset everything to stock", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(done?.let { "Done: $it setting${if (it == 1) "" else "s"} put back." }
                ?: "Puts back every setting HandyTuner changed: brightness, refresh rate, scanning, Private DNS, button layout, stick lights, sleep underclock, frame caps, resolution.",
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
                Text(c.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
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
