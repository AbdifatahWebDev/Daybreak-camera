@file:OptIn(ExperimentalTextApi::class)

package com.brekwol.daybreak.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Daybreak color tokens. Names match the Daybreak design system. */
@Immutable
data class DaybreakColors(
    val surface000: Color,
    val surface100: Color,
    val surface200: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val sun: Color,
    val sunGlow: Color,
    val onSun: Color,
    val record: Color,
    val focus: Color,
    val hdr: Color,
    val scrim: Color,
    val isDark: Boolean,
)

/** Capture, viewer and editor: always this theme. */
val Darkroom = DaybreakColors(
    surface000 = Color(0xFF050505),
    surface100 = Color(0xFF121110),
    surface200 = Color(0xFF1F1D1A),
    line = Color(0xFF34302B),
    ink = Color(0xFFF4EFE6),
    inkMuted = Color(0xFFA39D93),
    sun = Color(0xFFFFB020),
    sunGlow = Color(0xFF3A2A0C),
    onSun = Color(0xFF1A1100),
    record = Color(0xFFFF5A47),
    focus = Color(0xFF6FDCAA),
    hdr = Color(0xFF8CC8FF),
    scrim = Color(0x9E050505),
    isDark = true,
)

/** Library and settings when the phone is in light mode. */
val Daylight = DaybreakColors(
    surface000 = Color(0xFFFBFAF7),
    surface100 = Color(0xFFF2EFE9),
    surface200 = Color(0xFFE6E1D8),
    line = Color(0xFFCFC8BC),
    ink = Color(0xFF17140F),
    inkMuted = Color(0xFF5D574E),
    sun = Color(0xFF9A5A00),
    sunGlow = Color(0xFFFBE7C2),
    onSun = Color(0xFFFFFFFF),
    record = Color(0xFFC42A1B),
    focus = Color(0xFF0B6E49),
    hdr = Color(0xFF1B65A8),
    scrim = Color(0x9E050505),
    isDark = false,
)

val InterTight = FontFamily(
    Font(R.font.inter_tight, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_tight, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_tight, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** Daybreak type styles. Numerals always use the mono face. */
@Immutable
data class DaybreakType(
    val display: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em),
    val title: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    val body: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.01.em),
    val caption: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    val mode: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.12.em),
    val readoutLarge: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp),
    val readout: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
)

private val LocalDaybreakColors = staticCompositionLocalOf { Darkroom }
private val LocalDaybreakType = staticCompositionLocalOf { DaybreakType() }

/** Access point for Daybreak tokens inside composables. */
object Daybreak {
    val colors: DaybreakColors
        @Composable get() = LocalDaybreakColors.current
    val type: DaybreakType
        @Composable get() = LocalDaybreakType.current
}

/**
 * Wraps content in the Daybreak theme. Capture and viewer screens pass
 * [darkroom] = true; library and settings follow the system setting.
 */
@Composable
fun DaybreakTheme(
    darkroom: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkroom) Darkroom else Daylight
    val type = DaybreakType()
    val scheme = if (darkroom) {
        darkColorScheme(
            primary = colors.sun,
            onPrimary = colors.onSun,
            primaryContainer = colors.sunGlow,
            onPrimaryContainer = colors.sun,
            background = colors.surface000,
            onBackground = colors.ink,
            surface = colors.surface100,
            onSurface = colors.ink,
            surfaceVariant = colors.surface200,
            onSurfaceVariant = colors.inkMuted,
            surfaceContainerHigh = colors.surface100,
            outline = colors.line,
            error = colors.record,
        )
    } else {
        lightColorScheme(
            primary = colors.sun,
            onPrimary = colors.onSun,
            primaryContainer = colors.sunGlow,
            onPrimaryContainer = colors.ink,
            background = colors.surface000,
            onBackground = colors.ink,
            surface = colors.surface100,
            onSurface = colors.ink,
            surfaceVariant = colors.surface200,
            onSurfaceVariant = colors.inkMuted,
            surfaceContainerHigh = colors.surface100,
            outline = colors.line,
            error = colors.record,
        )
    }
    CompositionLocalProvider(
        LocalDaybreakColors provides colors,
        LocalDaybreakType provides type,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
