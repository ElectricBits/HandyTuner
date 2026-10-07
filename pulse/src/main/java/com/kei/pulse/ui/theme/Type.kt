package com.kei.pulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kei.pulse.R

// Display: Chakra Petch (angular, technical). Body/data: IBM Plex Mono (instrument readout).
val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

val PulseTypography = Typography(
    displayLarge   = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 0.04.em),
    displayMedium  = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.04.em),
    headlineMedium = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.03.em),
    headlineSmall  = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.03.em),
    titleLarge     = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.02.em),
    titleMedium    = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.03.em),
    titleSmall     = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.04.em),
    bodyLarge      = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = 0.01.em),
    bodyMedium     = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.01.em),
    bodySmall      = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.02.em),
    labelLarge     = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.08.em),
    labelMedium    = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.12.em),
    labelSmall     = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.16.em),
)
