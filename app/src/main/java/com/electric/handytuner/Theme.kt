// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The design board (docs/design/board-1.png) as code. */
object Hand {
    val Blue = Color(0xFF0389FB)
    val Good = Color(0xFF39FF14)
    val Warn = Color(0xFFFFB300)
    val Bad = Color(0xFFFF3B3B)
    val Alert = Color(0xFFFF2BD6)
    val Muted = Color(0xFF7A8BA0)
    val Card = Color(0xFF0A1428)          // navy card on true black

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Nunito = FontFamily(
        Font(R.font.nunito, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
        Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.nunito, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    )
    val Mono = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))
}

@Composable
fun HandyTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Hand.Nunito)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hand.Blue, onPrimary = Color.White, secondaryContainer = Hand.Blue.copy(alpha = 0.25f),
            onSecondaryContainer = Color.White, background = Color.Black, surface = Color.Black, onSurface = Color.White,
            outline = Hand.Blue.copy(alpha = 0.6f),
        ),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 16.sp), bodyMedium = base.copy(fontSize = 14.sp),
            labelLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            titleLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
        ),
        content = content,
    )
}

/** The board's focus highlight: a glowing blue outline on whatever the D-pad is on. */
fun Modifier.glowFocus(shape: RoundedCornerShape = RoundedCornerShape(16.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }.border(if (focused) 3.dp else 0.dp, if (focused) Hand.Blue else Color.Transparent, shape)
}

/** Navy card with a thin blue outline. */
@Composable
fun HandCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.background(Hand.Card, RoundedCornerShape(16.dp)).border(1.dp, Hand.Blue.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
        .padding(16.dp),
    content = content,
)

/** Sub-page tabs inside one rail page: one chip per group; [pick] gets the index. */
@Composable
fun SubTabs(labels: List<String>, selected: Int, pick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.padding(bottom = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            androidx.compose.material3.FilterChip(selected = i == selected, onClick = { pick(i) }, label = { Text(label) },
                modifier = Modifier.glowFocus(RoundedCornerShape(8.dp)))
        }
    }
}

/** A titled row of choices (presets editor, Tuning page); [pick] gets the index. */
@Composable
fun chips(title: String, options: List<Pair<String, Boolean>>, enabled: Boolean = true, pick: (Int) -> Unit) {
    Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    androidx.compose.foundation.layout.Row {
        options.forEachIndexed { i, (label, on) ->
            androidx.compose.material3.FilterChip(selected = on, enabled = enabled, onClick = { pick(i) }, label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp).glowFocus(RoundedCornerShape(8.dp)))
        }
    }
}
