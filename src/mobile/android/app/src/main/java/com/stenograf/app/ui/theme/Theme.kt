package com.stenograf.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stenograf.app.R

/**
 * Оформление RECO: «ретро-рабочий стол» — бумажный фон, окна с толстой обводкой
 * и жёсткой тенью, моноширинный шрифт. Акцентные цвета взяты из иконки приложения.
 */
@Immutable
data class RecoColors(
    /** Фон «рабочего стола». */
    val desktop: Color,
    /** Декоративные полосы на фоне. */
    val bandA: Color,
    val bandB: Color,
    /** Заливка окон и карточек. */
    val window: Color,
    /** Заголовок окна, панели. */
    val titleBar: Color,
    /** Обводка, основной текст. */
    val ink: Color,
    /** Второстепенный текст. */
    val muted: Color,
    /** Жёсткая тень под окнами и кнопками. */
    val shadow: Color,
    /** Подсветка текущей реплики. */
    val highlight: Color,
    /** Неактивная часть индикаторов. */
    val track: Color,
    val danger: Color,
    val isDark: Boolean,
)

/** Цвета из иконки (звуковая волна): красный, оранжевый, янтарный, жёлтый, томатный. */
object RecoPalette {
    val Red = Color(0xFFE8432E)
    val Orange = Color(0xFFF2792B)
    val Amber = Color(0xFFF6A623)
    val Yellow = Color(0xFFF7D33C)
    val Tomato = Color(0xFFEE5A2C)
    val Wave = listOf(Red, Orange, Amber, Yellow, Tomato)

    /** Тёмный фон иконки; им же подписываются цветные заливки — на них всегда тёмный текст. */
    val IconInk = Color(0xFF121110)
}

private val Light = RecoColors(
    desktop = Color(0xFFF3EBDA),
    bandA = Color(0xFFF5DDAE),
    bandB = Color(0xFFF2CDA4),
    window = Color(0xFFFFFBF2),
    titleBar = Color(0xFFF8EED8),
    ink = Color(0xFF121110),
    muted = Color(0xFF6B6257),
    shadow = Color(0xFF121110),
    highlight = Color(0xFFFFEFA8),
    track = Color(0xFFE9DFC9),
    danger = Color(0xFFC9301C),
    isDark = false,
)

private val Dark = RecoColors(
    desktop = Color(0xFF121110),
    bandA = Color(0xFF1C1915),
    bandB = Color(0xFF231C16),
    window = Color(0xFF1D1A17),
    titleBar = Color(0xFF27221C),
    ink = Color(0xFFF3EBDA),
    muted = Color(0xFFA89C8B),
    shadow = Color(0xFF5A4E40),
    highlight = Color(0xFF3B3220),
    track = Color(0xFF332D26),
    danger = Color(0xFFFF7A62),
    isDark = true,
)

private val LocalRecoColors = staticCompositionLocalOf { Light }

object Reco {
    val colors: RecoColors
        @Composable @ReadOnlyComposable get() = LocalRecoColors.current
}

/** IBM Plex Mono (SIL Open Font License 1.1) — кириллица и латиница. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) = TextStyle(
    fontFamily = PlexMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private val AppTypography = Typography(
    displayLarge = mono(52, 60, FontWeight.Bold, -1.5),
    displayMedium = mono(44, 52, FontWeight.Bold, -1.0),
    displaySmall = mono(34, 42, FontWeight.Bold, -0.5),
    headlineLarge = mono(30, 38, FontWeight.Bold, -0.5),
    headlineMedium = mono(26, 34, FontWeight.Bold, -0.5),
    headlineSmall = mono(22, 30, FontWeight.Bold, -0.3),
    titleLarge = mono(19, 26, FontWeight.Bold),
    titleMedium = mono(16, 22, FontWeight.Bold),
    titleSmall = mono(14, 20, FontWeight.Bold),
    bodyLarge = mono(15, 23),
    bodyMedium = mono(14, 21),
    bodySmall = mono(12, 18),
    labelLarge = mono(14, 20, FontWeight.Bold),
    labelMedium = mono(12, 16, FontWeight.SemiBold),
    labelSmall = mono(11, 14, FontWeight.SemiBold, 0.3),
)

/** Цифры одинаковой ширины (в моноширинном шрифте и так одинаковые, но явно — надёжнее). */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Цвет записи (красная точка) — красный из иконки. */
val RecordRed = RecoPalette.Red

/**
 * Цвета участников — яркие заливки с тёмной подписью и обводкой. Красный не используется:
 * он означает запись и удаление.
 */
private val SpeakerFills = listOf(
    Color(0xFFF6A623), Color(0xFF7CC6C2), Color(0xFFF4A6C6), Color(0xFF9AD08A),
    Color(0xFF8FB4F0), Color(0xFFF2792B), Color(0xFFC3A6F0), Color(0xFFF7D33C),
)

fun speakerColor(index: Int): Color = SpeakerFills[Math.floorMod(index, SpeakerFills.size)]

@Composable
fun RecoTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (c.isDark) {
        darkColorScheme(
            primary = c.ink, onPrimary = c.window,
            primaryContainer = c.titleBar, onPrimaryContainer = c.ink,
            secondary = c.muted, onSecondary = c.window,
            secondaryContainer = c.titleBar, onSecondaryContainer = c.ink,
            tertiary = RecoPalette.Orange,
            background = c.desktop, onBackground = c.ink,
            surface = c.window, onSurface = c.ink,
            surfaceVariant = c.titleBar, onSurfaceVariant = c.muted,
            surfaceContainerLowest = c.window, surfaceContainerLow = c.window, surfaceContainer = c.window,
            surfaceContainerHigh = c.window, surfaceContainerHighest = c.titleBar,
            outline = c.ink, outlineVariant = c.track,
            error = c.danger, onError = RecoPalette.IconInk,
        )
    } else {
        lightColorScheme(
            primary = c.ink, onPrimary = c.window,
            primaryContainer = c.titleBar, onPrimaryContainer = c.ink,
            secondary = c.muted, onSecondary = c.window,
            secondaryContainer = c.titleBar, onSecondaryContainer = c.ink,
            tertiary = RecoPalette.Orange,
            background = c.desktop, onBackground = c.ink,
            surface = c.window, onSurface = c.ink,
            surfaceVariant = c.titleBar, onSurfaceVariant = c.muted,
            surfaceContainerLowest = c.window, surfaceContainerLow = c.window, surfaceContainer = c.window,
            surfaceContainerHigh = c.window, surfaceContainerHighest = c.titleBar,
            outline = c.ink, outlineVariant = c.track,
            error = c.danger, onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalRecoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
