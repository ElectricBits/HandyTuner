// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Appearance, the one-off fixes, diagnostics (checks, log, safe mode, reset) and About. */
@Composable
fun SystemPage(act: MainActivity) {
    val actions = act.actions
    val scope = rememberCoroutineScope()
    val open = openSub()

    ConsolePage {
        RowGroup("Appearance") {
            var light by remember { mutableStateOf(Hand.light) }
            // The theme reaches the overlay too, so it goes through the HUD's file, which the overlay watches.
            ChoiceRow("Theme", listOf(false, true), if (light) 1 else 0, { if (it) "Light" else "Dark" },
                hint = "The app, the HUD and the Quick Menu", icon = Icons.Rounded.DarkMode) { i ->
                light = i == 1
                HudStyle.save(act, HudStyle.load(act).copy(light = light)); Hand.light = light
            }
        }

        RowGroup("Odin") {
            var removed by remember { mutableStateOf(!actions.odinMenuOn()) }
            var menuBusy by remember { mutableStateOf(false) }
            ToggleRow("Remove Odin's swipe-in menu", removed, enabled = !menuBusy, hint = "Takes effect after a restart",
                help = "Swiping in from the right edge opens Odin's own game panel. On disables the GameAssistant app behind it, " +
                    "but Odin keeps that app running, so the panel only goes away after you restart the Odin. Off turns it back " +
                    "on, again after a restart. Reset everything to stock also turns it back on.", icon = Icons.Rounded.SwipeLeft) { want ->
                menuBusy = true
                scope.launch(Dispatchers.IO) {
                    actions.setOdinMenu(!want)
                    val now = !actions.odinMenuOn()
                    withContext(Dispatchers.Main) { removed = now; menuBusy = false }
                }
            }
        }

        RowGroup("Diagnostics") {
            var checks by remember { mutableStateOf<Pair<List<Diagnostics.Check>, List<Diagnostics.Check>>?>(null) }
            LaunchedEffect(act.resumes) {
                checks = withContext(Dispatchers.IO) { Pair(Diagnostics.setup(act), Diagnostics.apps(act)) }
                    // One line each in logcat, so a report can be read off the device without screenshots.
                    .also { (a, b) -> (a + b).forEach { android.util.Log.i("HandyTuner", "diag ${it.status} ${it.name}: ${it.detail}") } }
            }
            val c = checks
            val toFix = c?.let { (a, b) -> (a + b).count { it.status != Diagnostics.Status.OK } }
            NavRow("Permissions & apps", hint = "What HandyTuner can reach on this Odin", icon = Icons.Rounded.VerifiedUser,
                value = when { toFix == null -> "Checking…"; toFix == 0 -> "All OK"; else -> "$toFix to look at" }, enabled = c != null) { from ->
                open("Permissions & apps", from) { ChecksList(act) }
            }

            var logNote by remember { mutableStateOf<String?>(null) }
            ActionRow("Export log file", hint = "For a bug report: saved to Downloads", value = logNote, icon = Icons.Rounded.SaveAlt) {
                logNote = "Saving…"
                scope.launch(Dispatchers.IO) {
                    val name = Diagnostics.exportLog(act)
                    withContext(Dispatchers.Main) { logNote = if (name != null) "Saved: $name" else "Couldn't save" }
                }
            }
            ActionRow("Run setup again", hint = "The first-start walkthrough", icon = Icons.Rounded.Replay) { act.runSetupAgain() }

            var safe by remember { mutableStateOf(SafeMode.active(act)) }
            if (safe) {
                val since = remember { SafeMode.since(act)?.let { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "unknown" }
                // Deliberately not in the overlay: clearing it says "start writing to my device again", the owner's call with the app open.
                ActionRow("Clear safe mode", hint = "On since $since — ${SafeMode.why(act)}", icon = Icons.Rounded.HealthAndSafety,
                    help = "HandyTuner isn't changing anything until you clear this. What it changed before is still on the Odin; " +
                        "Reset everything to stock puts that back.") {
                    SafeMode.set(act, false); safe = false
                    android.util.Log.i("HandyTuner", "safe mode cleared by the owner")
                }
            }

            var done by remember { mutableStateOf<Int?>(null) }
            ActionRow("Reset everything to stock", danger = true, confirm = "reset everything", icon = Icons.Rounded.RestartAlt,
                hint = done?.let { "Done: $it setting${if (it == 1) "" else "s"} put back" } ?: "Puts back every setting HandyTuner changed",
                help = "Brightness, refresh rate, scanning, Private DNS, button layout, stick lights, sleep underclock, frame caps, " +
                    "resolution and the Odin swipe-in menu. Hotkeys and the HUD's look are HandyTuner's own, so they stay.") {
                scope.launch(Dispatchers.IO) {
                    val n = Originals.saved(act).size
                    Originals.restore(act)
                    if (File(act.filesDir, "gms_off").exists()) actions.setPlayServices(true)
                    if (File(act.filesDir, "odin_menu_off").exists()) actions.setOdinMenu(true)
                    Resolution.save(act, Resolution()); Resolution.putBack(act)
                    File(act.filesDir, "reset").writeText(System.currentTimeMillis().toString())
                    android.util.Log.i("HandyTuner", "reset to stock: $n settings")
                    withContext(Dispatchers.Main) { done = n }
                }
            }
        }

        RowGroup("About") {
            fun web(url: String) = runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val version = remember { runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: "" }
            // The notice GPL-2.0 §2(c) asks an interactive program to show: copyright, no warranty, how to read the license.
            Panel {
                Text("HandyTuner $version", color = Hand.Text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                Text("Copyright (C) 2026 ElectricBits. Includes the PULSE engine, copyrighted by keiretrogaming and its contributors " +
                    "(built on ClusterTune by AurelioB and O2P Tweaks by FeralAI). Free software under the GNU General Public License " +
                    "version 2: you may share and change it under its terms. It comes with ABSOLUTELY NO WARRANTY; see Licenses. " +
                    "Not made by or affiliated with AYN.", color = Hand.Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Text("Beta: tested on the AYN Odin 2 Portal only.", color = Hand.Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            NavRow("What's new", hint = "What changed in this version", icon = Icons.Rounded.NewReleases) { from -> open("What's new", from) { WhatsNewPage(act) } }
            NavRow("Licenses", hint = "Every license, in full", icon = Icons.Rounded.Article) { from -> open("Licenses", from) { LicensesText(act) } }
            ActionRow("Source code", hint = "github.com/ElectricBits/HandyTuner", icon = Icons.Rounded.Code) { web(About.SOURCE) }
            if (About.DISCORD.isNotEmpty()) ActionRow("Discord", hint = "Questions and ideas", icon = Icons.Rounded.Forum) { web(About.DISCORD) }
            if (About.KOFI.isNotEmpty()) ActionRow("Support on Ko-fi", hint = "If HandyTuner helps you", icon = Icons.Rounded.Favorite) { web(About.KOFI) }
        }
    }
}

/** Each check: OK, missing, or needs access (with a Fix that opens the right settings screen). */
@Composable
private fun ChecksList(act: MainActivity) = ConsolePage {
    // Checked again on every return to the app, so a Fix done in Android's settings shows as OK straight away
    // (the list used to keep what it saw when it was opened).
    var checks by remember { mutableStateOf<Pair<List<Diagnostics.Check>, List<Diagnostics.Check>>?>(null) }
    LaunchedEffect(act.resumes) { checks = withContext(Dispatchers.IO) { Pair(Diagnostics.setup(act), Diagnostics.apps(act)) } }
    val (setup, apps) = checks ?: run { Note("Checking…"); return@ConsolePage }
    @Composable fun rows(list: List<Diagnostics.Check>) = list.forEach { c ->
        val fix = c.fix
        if (c.status == Diagnostics.Status.GRANT && fix != null)
            ActionRow(c.name, hint = c.detail, value = if (fix.action == Intent.ACTION_VIEW) "Get" else "Fix", icon = Icons.Rounded.Shield) { act.startActivity(fix) }
        else InfoRow(c.name, when (c.status) { Diagnostics.Status.OK -> "OK"; Diagnostics.Status.MISSING -> "Missing"; else -> "Needs access" },
            hint = c.detail, valueColor = when (c.status) { Diagnostics.Status.OK -> Hand.Good; Diagnostics.Status.MISSING -> Hand.Bad; else -> Hand.Warn })
    }
    RowGroup("Permissions & access") { rows(setup) }
    RowGroup("Apps HandyTuner works with") { rows(apps) }
}

/** The license texts: one long page the D-pad scrolls (▲▼), since plain text has nothing to focus. */
@Composable
private fun LicensesText(act: MainActivity) {
    val text = remember {
        runCatching { act.assets.list("legal")!!.sorted().joinToString("\n\n\n") { f ->
            "═══ $f ═══\n\n" + act.assets.open("legal/$f").bufferedReader().use { it.readText() } } }.getOrDefault("Couldn't read the licenses.")
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val hints = LocalHints.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxSize().padding(bottom = 16.dp).background(Hand.Card, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Hand.Blue else Hand.Blue.copy(alpha = 0.18f), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) hints.kind = RowKind.SCROLL }
            .onPreviewKeyEvent { e ->
                val d = when (e.key) { Key.DirectionDown -> 400f; Key.DirectionUp -> -400f; else -> return@onPreviewKeyEvent false }
                if (e.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(d) }
                true
            }
            .focusable().verticalScroll(scroll).padding(20.dp),
    ) { Text(text, color = Hand.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp) }
}
