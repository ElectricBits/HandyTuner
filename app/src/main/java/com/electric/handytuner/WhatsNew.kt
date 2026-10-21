// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            "Key mapping is gone, so the app stays simple. Your saved layouts stay on the Odin.",
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

/** The full notes (System › About › What's new): who and what on the left, the notes on the right. */
@Composable
fun WhatsNewPage(ctx: Context) {
    // Two columns, so the whole screen fits without scrolling: who and what on the left, the notes on the right.
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            HelperAvatar(talking = false, mood = Mood.IDLE, hops = 0, width = 120.dp, dance = 0)
            Text("What's new", color = Hand.Text, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, modifier = Modifier.padding(top = 8.dp))
            Text("HandyTuner ${WhatsNew.version(ctx)}", color = Hand.Muted, fontSize = 16.sp)
            RowGroup("Support HandyTuner") {
                ActionRow("Support on Ko-fi", hint = "Free app; a coffee keeps it going", icon = Icons.Rounded.Favorite) {
                    open(ctx, About.KOFI)
                }
                ActionRow("Join the Discord", hint = "Questions, ideas and news", icon = Icons.Rounded.Forum) { open(ctx, About.DISCORD) }
            }
        }
        Column(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState())) {
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
        }
    }
}

private fun open(ctx: Context, url: String) =
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

/** Home's What's New card: the notes across the page, Ko-fi and Discord, and "Got it" to put it away until the next update. */
@Composable
fun WhatsNewCard(ctx: Context, onDone: () -> Unit) {
    RowGroup("What's new in ${WhatsNew.version(ctx)}") {
        Panel {
            WhatsNew.NOTES.first().forEach { line ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("•", color = Hand.Blue, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 12.dp))
                    Text(line, color = Hand.Text, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
        ActionRow("Support on Ko-fi", hint = "Free app; a coffee keeps it going", icon = Icons.Rounded.Favorite) { open(ctx, About.KOFI) }
        ActionRow("Join the Discord", hint = "Questions, ideas and news", icon = Icons.Rounded.Forum) { open(ctx, About.DISCORD) }
        ActionRow("Got it", hint = "Hides this until the next update; System › About shows it again", icon = Icons.Rounded.CheckCircle) { onDone() }
    }
}
