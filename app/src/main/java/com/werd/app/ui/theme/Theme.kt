package com.werd.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.werd.app.R
import com.werd.app.data.ThemeMode
import androidx.compose.ui.unit.dp

val Emerald = Color(0xFF0E6B4E)
val EmeraldDeep = Color(0xFF08452F)
val Gold = Color(0xFFC19A3E)
val GoldSoft = Color(0xFFE9D7A6)

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
)
val Amiri = FontFamily(
    Font(R.font.amiri_regular, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)

/** Accent colours users can pick for a werd or group. */
val WerdPalette = listOf(
    Color(0xFF0E6B4E), Color(0xFFB7862B), Color(0xFF2B6C8F), Color(0xFF8C4A6B),
    Color(0xFF5E7D2E), Color(0xFFA0522D), Color(0xFF4B5BA6), Color(0xFF2F7D7A),
)

/** The colour themes the user can choose (Settings → Appearance). */
enum class Palette { STANDARD, MISBAHA, DAWN, CLASSIC }

@Immutable
data class ExtraColors(
    val done: Color,
    val onDone: Color,
    /** The Today card and other "hero" surfaces, and the text on them. */
    val hero: Color,
    val onHero: Color,
    val heroMuted: Color,
    /** Beads: counted, not yet counted (outline), and the one being said now (ring). */
    val bead: Color,
    val beadEmpty: Color,
    val beadCurrent: Color,
    /** Beads on the hero surface. */
    val heroBead: Color,
    val heroBeadEmpty: Color,
    val gold: Color,
    val cardBorder: Color,
    /** Kept for screens not yet moved to the hero surface. */
    val headerTop: Color = hero,
    val headerBottom: Color = hero,
    val pattern: Color = gold,
)

val LocalExtra = staticCompositionLocalOf { extraFor(Palette.STANDARD, false) }

private data class Scheme(val light: androidx.compose.material3.ColorScheme, val dark: androidx.compose.material3.ColorScheme)

private fun light(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
    background: Long, surface: Long, ink: Long, muted: Long, outline: Long, outlineVariant: Long,
    c0: Long, c1: Long, c2: Long, c3: Long, c4: Long,
) = lightColorScheme(
    primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color.White, secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(0xFF2B6C8F), onTertiary = Color.White, tertiaryContainer = Color(0xFFD3E8F3), onTertiaryContainer = Color(0xFF07293B),
    background = Color(background), onBackground = Color(ink), surface = Color(surface), onSurface = Color(ink),
    surfaceVariant = Color(c3), onSurfaceVariant = Color(muted), surfaceTint = Color(primary),
    outline = Color(outline), outlineVariant = Color(outlineVariant), error = Color(0xFFB3261E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(c0), surfaceContainer = Color(c1),
    surfaceContainerHigh = Color(c2), surfaceContainerHighest = Color(c4), surfaceBright = Color(surface), surfaceDim = Color(c4),
)

private fun dark(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
    background: Long, surface: Long, ink: Long, muted: Long, outline: Long, outlineVariant: Long,
    c0: Long, c1: Long, c2: Long, c3: Long, c4: Long,
) = darkColorScheme(
    primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color(0xFF1C1408), secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(0xFF94CCEB), onTertiary = Color(0xFF00344A),
    background = Color(background), onBackground = Color(ink), surface = Color(surface), onSurface = Color(ink),
    surfaceVariant = Color(c3), onSurfaceVariant = Color(muted), surfaceTint = Color(primary),
    outline = Color(outline), outlineVariant = Color(outlineVariant), error = Color(0xFFF2B8B5),
    surfaceContainerLowest = Color(background), surfaceContainerLow = Color(c0), surfaceContainer = Color(c1),
    surfaceContainerHigh = Color(c2), surfaceContainerHighest = Color(c4), surfaceBright = Color(c4), surfaceDim = Color(background),
)

private val schemes = mapOf(
    // warm ivory, white cards, one green action colour
    Palette.STANDARD to Scheme(
        light(0xFF127A57, 0xFFFFFFFF, 0xFFD5EEE2, 0xFF04301F, 0xFF8A6A2E, 0xFFF1E6CC, 0xFF3A2B0C,
            0xFFF7F3EC, 0xFFFFFFFF, 0xFF22201C, 0xFF6E675C, 0xFFCFC6B6, 0xFFEAE3D6,
            0xFFFBF8F2, 0xFFF3EEE5, 0xFFEFE9DE, 0xFFEFE9DE, 0xFFE8E1D3),
        dark(0xFF6FD3A8, 0xFF00391F, 0xFF0F5A3F, 0xFFC4F1DB, 0xFFE0C287, 0xFF4A3A18, 0xFFF6E2B5,
            0xFF121210, 0xFF1A1A17, 0xFFECE8DF, 0xFFB0AA9C, 0xFF5A564C, 0xFF2E2C27,
            0xFF1C1B18, 0xFF22211D, 0xFF2A2924, 0xFF2A2924, 0xFF33312B)),
    // walnut, amber and ivory: the prayer beads themselves
    Palette.MISBAHA to Scheme(
        light(0xFF8A5A1E, 0xFFFFFFFF, 0xFFF3DDB4, 0xFF2E1B05, 0xFF8A5A1E, 0xFFF3DDB4, 0xFF2E1B05,
            0xFFF6F0E4, 0xFFFFFCF6, 0xFF2A2017, 0xFF7A6A57, 0xFFD3C4AA, 0xFFEADDC6,
            0xFFFBF6EC, 0xFFF3EADA, 0xFFEFE4D0, 0xFFEFE4D0, 0xFFE7DAC2),
        dark(0xFFE9A846, 0xFF2A1700, 0xFF5A3A12, 0xFFFFDDB0, 0xFFE9C98F, 0xFF4A3418, 0xFFFFE3B8,
            0xFF17110B, 0xFF1F1710, 0xFFF3E6CF, 0xFFCDB894, 0xFF6B5235, 0xFF3A2C1D,
            0xFF211912, 0xFF2A1D12, 0xFF33251A, 0xFF33251A, 0xFF3D2D20)),
    // the hour before Fajr: indigo and dawn peach
    Palette.DAWN to Scheme(
        light(0xFF3D3A7A, 0xFFFFFFFF, 0xFFE4E0F4, 0xFF15134A, 0xFFB35A3C, 0xFFFDE4D8, 0xFF3E1608,
            0xFFF4F1F8, 0xFFFFFFFF, 0xFF1C1B2E, 0xFF6E6A84, 0xFFC9C4DC, 0xFFE6E2F0,
            0xFFF9F7FC, 0xFFF0EDF6, 0xFFEAE6F2, 0xFFEAE6F2, 0xFFE2DDEC),
        dark(0xFFC3BEF5, 0xFF221F5C, 0xFF3A3775, 0xFFE4E0FF, 0xFFFFC9A8, 0xFF5A2E1E, 0xFFFFE2D2,
            0xFF15142B, 0xFF1C1B36, 0xFFF2EEFA, 0xFFB9B3D8, 0xFF56527A, 0xFF2C2A4A,
            0xFF1F1E3B, 0xFF252445, 0xFF2D2B52, 0xFF2D2B52, 0xFF35335C)),
    // Werd's original green and gold
    Palette.CLASSIC to Scheme(
        light(0xFF0E6B4E, 0xFFFFFFFF, 0xFFCBE7DA, 0xFF05301F, 0xFFA67C24, 0xFFF3E4BC, 0xFF3B2A05,
            0xFFFBF7EE, 0xFFFFFCF5, 0xFF26302B, 0xFF5E5B4F, 0xFFCDBF9B, 0xFFE8DFC9,
            0xFFFDF9F0, 0xFFF8F2E4, 0xFFF4EDDC, 0xFFF2EAD8, 0xFFEFE6D2),
        dark(0xFF7FD1AE, 0xFF003824, 0xFF0B5139, 0xFFB9EFD3, 0xFFE0BE72, 0xFF5A4512, 0xFFFCE3A8,
            0xFF0F1613, 0xFF141C18, 0xFFE6E2D6, 0xFFBDB7A6, 0xFF6E6957, 0xFF34403A,
            0xFF151E1A, 0xFF19231F, 0xFF202B26, 0xFF26302B, 0xFF28342E)),
)

/** The light ground of a palette (Settings shows it on each colour tile). */
fun groundFor(p: Palette): Color = schemes.getValue(p).light.background

fun extraFor(p: Palette, dark: Boolean): ExtraColors = when (p) {
    Palette.STANDARD -> if (!dark) ExtraColors(
        done = Color(0xFFE6F3EC), onDone = Color(0xFF0C5A3F), hero = Color(0xFF22201C), onHero = Color(0xFFF7F3EC), heroMuted = Color(0xFFBDB5A6),
        bead = Color(0xFF127A57), beadEmpty = Color(0xFF8F8778), beadCurrent = Color(0xFF22201C),
        heroBead = Color(0xFF6FD3A8), heroBeadEmpty = Color(0xFF6E675C), gold = Color(0xFF8A6A2E), cardBorder = Color(0xFFEAE3D6))
    else ExtraColors(
        done = Color(0xFF173528), onDone = Color(0xFFA6E8C9), hero = Color(0xFF26251F), onHero = Color(0xFFF7F3EC), heroMuted = Color(0xFFB0AA9C),
        bead = Color(0xFF6FD3A8), beadEmpty = Color(0xFF8A857A), beadCurrent = Color(0xFFF7F3EC),
        heroBead = Color(0xFF6FD3A8), heroBeadEmpty = Color(0xFF7E796E), gold = Color(0xFFE0C287), cardBorder = Color(0xFF2E2C27))
    Palette.MISBAHA -> if (!dark) ExtraColors(
        done = Color(0xFFEEF1E4), onDone = Color(0xFF4E6338), hero = Color(0xFF2A1D12), onHero = Color(0xFFF3E6CF), heroMuted = Color(0xFFCDB894),
        bead = Color(0xFFD9902B), beadEmpty = Color(0xFF92806A), beadCurrent = Color(0xFF5B3A1E),
        heroBead = Color(0xFFD9902B), heroBeadEmpty = Color(0xFF8C7558), gold = Color(0xFF8A5A1E), cardBorder = Color(0xFFEADDC6))
    else ExtraColors(
        done = Color(0xFF26301B), onDone = Color(0xFFC6DAA4), hero = Color(0xFF2A1D12), onHero = Color(0xFFF3E6CF), heroMuted = Color(0xFFCDB894),
        bead = Color(0xFFE9A846), beadEmpty = Color(0xFF9A7F5C), beadCurrent = Color(0xFFF3E6CF),
        heroBead = Color(0xFFE9A846), heroBeadEmpty = Color(0xFF6B5235), gold = Color(0xFFE9A846), cardBorder = Color(0xFF3A2C1D))
    Palette.DAWN -> if (!dark) ExtraColors(
        done = Color(0xFFF6EBD8), onDone = Color(0xFF7A4E12), hero = Color(0xFF2B2A5C), onHero = Color(0xFFFFFFFF), heroMuted = Color(0xFFC9C3E6),
        bead = Color(0xFFE08A64), beadEmpty = Color(0xFF8E89AE), beadCurrent = Color(0xFF2B2A5C),
        heroBead = Color(0xFFFFC9A8), heroBeadEmpty = Color(0xFF9993C4), gold = Color(0xFFB35A3C), cardBorder = Color(0xFFE6E2F0))
    else ExtraColors(
        done = Color(0xFF3A2E2A), onDone = Color(0xFFFFD9C2), hero = Color(0xFF26244C), onHero = Color(0xFFF2EEFA), heroMuted = Color(0xFFB9B3D8),
        bead = Color(0xFFFFC9A8), beadEmpty = Color(0xFF8C88B5), beadCurrent = Color(0xFFF2EEFA),
        heroBead = Color(0xFFFFC9A8), heroBeadEmpty = Color(0xFF8F8AC0), gold = Color(0xFFFFC9A8), cardBorder = Color(0xFF2C2A4A))
    Palette.CLASSIC -> if (!dark) ExtraColors(
        done = Color(0xFFE2F1E9), onDone = Color(0xFF0B5139), hero = Color(0xFF08452F), onHero = Color(0xFFFFFFFF), heroMuted = Color(0xFFBFD8CB),
        bead = Color(0xFF0E6B4E), beadEmpty = Color(0xFF9A8C68), beadCurrent = Color(0xFFA67C24),
        heroBead = Color(0xFFE9D7A6), heroBeadEmpty = Color(0xFF6FA68E), gold = Color(0xFFA67C24), cardBorder = Color(0xFFEADFC4),
        headerTop = Color(0xFF07402C), headerBottom = Color(0xFF0E6B4E), pattern = Color(0xFFE2C27A))
    else ExtraColors(
        done = Color(0xFF15302A), onDone = Color(0xFF9FE3C2), hero = Color(0xFF062A1D), onHero = Color(0xFFE6E2D6), heroMuted = Color(0xFF9CBDAD),
        bead = Color(0xFF7FD1AE), beadEmpty = Color(0xFF7F948A), beadCurrent = Color(0xFFE0BE72),
        heroBead = Color(0xFFE0BE72), heroBeadEmpty = Color(0xFF5E8F7A), gold = Color(0xFFE0BE72), cardBorder = Color(0xFF2E3A34),
        headerTop = Color(0xFF062A1D), headerBottom = Color(0xFF0B4F39), pattern = Color(0xFFE2C27A))
}

private fun typography(): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = Tajawal),
        displayMedium = b.displayMedium.copy(fontFamily = Tajawal),
        displaySmall = b.displaySmall.copy(fontFamily = Tajawal),
        headlineLarge = b.headlineLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        headlineMedium = b.headlineMedium.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        headlineSmall = b.headlineSmall.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        titleLarge = b.titleLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        titleMedium = b.titleMedium.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        titleSmall = b.titleSmall.copy(fontFamily = Tajawal, fontWeight = FontWeight.Medium),
        bodyLarge = b.bodyLarge.copy(fontFamily = Tajawal),
        bodyMedium = b.bodyMedium.copy(fontFamily = Tajawal),
        bodySmall = b.bodySmall.copy(fontFamily = Tajawal),
        labelLarge = b.labelLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        labelMedium = b.labelMedium.copy(fontFamily = Tajawal, fontWeight = FontWeight.Medium),
        labelSmall = b.labelSmall.copy(fontFamily = Tajawal, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun WerdTheme(mode: ThemeMode, palette: Palette = Palette.STANDARD, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val sc = schemes.getValue(palette)
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides extraFor(palette, dark)) {
        MaterialTheme(colorScheme = if (dark) sc.dark else sc.light, typography = typography(), shapes = shapes, content = content)
    }
}

/** Softer, larger corners: cards 20dp, sheets and hero cards 28dp. */
val shapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)
