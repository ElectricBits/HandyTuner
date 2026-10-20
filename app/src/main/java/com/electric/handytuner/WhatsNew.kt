// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * What changed in this version, shown once after each update (not on a first install: setup comes first) and on
 * demand from System › About. Add an entry at the top with every release, alongside its fastlane changelog.
 */
object WhatsNew {
    /** Newest first. The first entry is what the screen shows. */
    val NOTES = listOf(
        listOf(
            "A new look built for the controller: one list of sections, L1/R1 to switch, B to go back.",
            "Every setting is one row: ◀▶ changes it, and the row you're on explains itself.",
            "Home shows battery, network and PULSE at a glance, your last game, and your hotkeys.",
            "Performance now holds Speed Up, Google Play services and Odin Assistant's game detection.",
            "Buttons use the Odin's names, like Left and Right paddle; HUD colors stay the same in Light.",
            "The rumble test works on the Odin's own controls.",
        ),
    )

    private const val KEY = "whatsNewSeen"

    /** Show it now? Only for someone who had the app before this version and hasn't seen this version's notes. */
    fun due(ctx: Context, onboarded: Boolean): Boolean {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY, -1)
        if (seen < 0 && !onboarded) { markSeen(ctx); return false }   // a new install: setup is the welcome
        return seen < code(ctx)
    }

    fun markSeen(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong(KEY, code(ctx)).apply()

    private fun code(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
    fun version(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
}

/** The notes, the Ko-fi row, and (when [onDone] is given) a "Got it" row that the D-pad starts on. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WhatsNewPage(ctx: Context, onDone: (() -> Unit)?) {
    val gotIt = remember { FocusRequester() }
    // Start on "Got it", in D-pad mode: the app opens in touch mode, where nothing can take focus.
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    if (onDone != null) LaunchedEffect(Unit) {
        repeat(10) { delay(80); inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard); runCatching { gotIt.requestFocus() } }
    }
    ConsolePage {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            HelperAvatar(talking = false, mood = Mood.IDLE, hops = 0, width = 110.dp, dance = 0)
            Column(Modifier.padding(start = 18.dp)) {
                Text("What's new", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                Text("HandyTuner ${WhatsNew.version(ctx)}", color = Hand.Muted, fontSize = 16.sp)
            }
        }
        RowGroup("In this version") {
            Panel {
                WhatsNew.NOTES.first().forEach { line ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text("•", color = Hand.Blue, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 12.dp))
                        Text(line, color = Hand.Text, fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }
        }
        RowGroup("Support HandyTuner") {
            ActionRow("Support on Ko-fi", hint = "HandyTuner is free; a coffee keeps it going", icon = Icons.Rounded.Favorite, value = "ko-fi.com/electricbits") {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(About.KOFI)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (onDone != null) ActionRow("Got it", hint = "B works too", icon = Icons.Rounded.CheckCircle, requester = gotIt) { onDone() }
        }
    }
}
