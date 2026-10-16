// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.Activity
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The design board (docs/design/board-1.png) as code. The colours come from [Palette] and follow the theme. */
object Hand {
    /**
     * Light theme, for the app, the HUD and the Quick Menu at once. Compose state, so flipping it redraws
     * the app; the overlay reads it when it builds a view. Set by the HUD page's Theme row, and at startup
     * from [HudStyle].
     */
    var light by mutableStateOf(false)

    val Blue get() = pick(Palette.BLUE_D, Palette.BLUE_L)
    val Good get() = pick(Palette.GOOD_D, Palette.GOOD_L)
    val Warn get() = pick(Palette.WARN_D, Palette.WARN_L)
    val Bad get() = pick(Palette.BAD_D, Palette.BAD_L)
    val Alert get() = pick(Palette.MAGENTA_D, Palette.MAGENTA_L)
    val Muted get() = pick(Palette.MUTED_D, Palette.MUTED_L)
    val Card get() = pick(Palette.CARD_D, Palette.CARD_L)                // navy on true black; white on grey
    val Text get() = pick(Palette.TEXT_D, Palette.TEXT_L)
    val TextDim get() = pick(Palette.TEXT_DIM_D, Palette.TEXT_DIM_L)
    val Bg get() = pick(Palette.BG_D, Palette.BG_L)
    val Panel get() = pick(Palette.PANEL_D, Palette.PANEL_L)
    val Grad get() = pick(Palette.GRAD_D, Palette.GRAD_L)
    val Track get() = pick(Palette.TRACK_D, Palette.TRACK_L)
    val Bar get() = pick(Palette.BAR_D, Palette.BAR_L)
    val Glow get() = pick(Palette.GLOW_D, Palette.GLOW_L)
    val AfkBg get() = pick(Palette.AFK_BG_D, Palette.AFK_BG_L)
    val AfkText get() = pick(Palette.AFK_TEXT_D, Palette.AFK_TEXT_L)

    /** A picked HUD accent, darkened when the theme is light. */
    fun accent(argb: Int) = Palette.accent(argb, light)

    private fun pick(dark: Int, lightC: Int) = Color(if (light) lightC else dark)

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
    // Read the flag here too: flipping it then recomposes everything under the theme.
    val light = Hand.light
    MaterialTheme(
        colorScheme = if (light) lightColorScheme(
            primary = Hand.Blue, onPrimary = Color.White, secondaryContainer = Hand.Blue.copy(alpha = 0.18f),
            onSecondaryContainer = Hand.Text, background = Hand.Bg, surface = Hand.Bg, onSurface = Hand.Text,
            onBackground = Hand.Text, outline = Hand.Blue.copy(alpha = 0.6f),
            // Left unset, these are Material's lavender baseline: purple-grey dialogs, switch tracks and chip text.
            secondary = Hand.Blue, error = Hand.Bad, surfaceVariant = Hand.Bar, onSurfaceVariant = Hand.Muted,
            outlineVariant = Hand.Blue.copy(alpha = 0.25f), surfaceContainerLowest = Hand.Card, surfaceContainerLow = Hand.Card,
            surfaceContainer = Hand.Card, surfaceContainerHigh = Hand.Card, surfaceContainerHighest = Hand.Track,
        ) else darkColorScheme(
            primary = Hand.Blue, onPrimary = Color.White, secondaryContainer = Hand.Blue.copy(alpha = 0.25f),
            onSecondaryContainer = Color.White, background = Hand.Bg, surface = Hand.Bg, onSurface = Hand.Text,
            onBackground = Hand.Text, outline = Hand.Blue.copy(alpha = 0.6f),
        ),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 16.sp), bodyMedium = base.copy(fontSize = 14.sp),
            labelLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            titleLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp),
        ),
        content = content,
    )
    // The window's bars come from the dark system theme: match them to the page, dark icons on light.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = Hand.Bg.toArgb(); window.navigationBarColor = Hand.Bg.toArgb()
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) bars else 0, bars)
    }
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
