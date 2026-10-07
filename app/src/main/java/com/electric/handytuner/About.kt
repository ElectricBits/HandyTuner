// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object About {
    /** The only device HandyTuner has been tested on (Build.MODEL). */
    const val TESTED_MODEL = "Odin2 Portal"
    val untestedDevice get() = android.os.Build.MODEL != TESTED_MODEL

    const val SOURCE = "https://github.com/ElectricBits/HandyTuner"
    /** The owner's Ko-fi page (empty = no donate button). */
    const val KOFI = "https://ko-fi.com/electricbits"
    /** The community Discord invite (empty = no button). */
    const val DISCORD = "https://discord.gg/uGQQ2n36QR"
}

/**
 * Version, the license notice GPL-2.0 §2(c) asks an interactive program to show (copyright, no warranty, how
 * to read the license), where the source is, and the optional donate button.
 */
@Composable
fun AboutCard(ctx: Context) {
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var licenses by remember { mutableStateOf(false) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    HandCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("HandyTuner $version", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("Copyright (C) 2026 ElectricBits. Includes the PULSE engine, copyrighted by keiretrogaming and its " +
            "contributors (built on ClusterTune by AurelioB and O2P Tweaks by FeralAI). Free software under the GNU " +
            "General Public License version 2: you may share and change it under its terms. It comes with ABSOLUTELY " +
            "NO WARRANTY; tap Licenses for details. Not made by or affiliated with AYN.", color = Hand.Muted, fontSize = 14.sp)
        Text("Beta: tested on the AYN Odin 2 Portal only. Other devices aren't tested yet.", color = Hand.Warn,
            fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { open(About.SOURCE) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Source code") }
            Button(onClick = { licenses = true }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Licenses") }
            if (About.DISCORD.isNotEmpty())
                Button(onClick = { open(About.DISCORD) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("Discord") }
            if (About.KOFI.isNotEmpty())
                Button(onClick = { open(About.KOFI) }, Modifier.glowFocus(RoundedCornerShape(50))) { Text("♥ Support on Ko-fi") }
        }
    }
    if (licenses) {
        val text = remember {
            runCatching { ctx.assets.list("legal")!!.sorted().joinToString("\n\n\n") { f ->
                "═══ $f ═══\n\n" + ctx.assets.open("legal/$f").bufferedReader().use { it.readText() } } }.getOrDefault("Couldn't read the licenses.")
        }
        AlertDialog(onDismissRequest = { licenses = false },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } },
            title = { Text("Licenses") },
            text = { Text(text, fontSize = 12.sp, modifier = Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) })
    }
}

/** Shown on Home on any device but the tested one: what's tested, the risk, and the way back. */
@Composable
fun UntestedDeviceCard() {
    if (!About.untestedDevice) return
    HandCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text("⚠ Not tested on this device", color = Hand.Warn, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text("HandyTuner has only been tested on the AYN Odin 2 Portal. On this ${android.os.Build.MANUFACTURER} " +
            "${android.os.Build.MODEL} some features may not work, or may change settings they shouldn't. If anything " +
            "goes wrong, use Diagnostics › Reset everything to stock. Reports help: Diagnostics › Export log file.",
            color = Hand.Muted, fontSize = 14.sp)
    }
}
