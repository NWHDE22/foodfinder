package de.foodfinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.foodfinder.R

/** Slate board + chalk. Dark is the board itself; light is the same board under daylight (chalk-dust paper). */
private val Board = darkColorScheme(
    primary = Color(0xFFF2D46B), onPrimary = Color(0xFF221D06),
    primaryContainer = Color(0xFF4A4220), onPrimaryContainer = Color(0xFFFBEBB0),
    secondary = Color(0xFF8FD3B4), onSecondary = Color(0xFF07261A),
    secondaryContainer = Color(0xFF24443A), onSecondaryContainer = Color(0xFFC8EEDB),
    tertiary = Color(0xFFF2A3A3), onTertiary = Color(0xFF3A0B0B),
    tertiaryContainer = Color(0xFF5A2A2A), onTertiaryContainer = Color(0xFFFFD9D9),
    background = Color(0xFF18211E), onBackground = Color(0xFFECEDE6),
    surface = Color(0xFF18211E), onSurface = Color(0xFFECEDE6),
    surfaceVariant = Color(0xFF2B3732), onSurfaceVariant = Color(0xFFB4BEB6),
    surfaceContainerLowest = Color(0xFF131B18), surfaceContainerLow = Color(0xFF1C2622),
    surfaceContainer = Color(0xFF202B26), surfaceContainerHigh = Color(0xFF26322D), surfaceContainerHighest = Color(0xFF2E3B35),
    outline = Color(0xFF6E7C74), outlineVariant = Color(0xFF3B4842),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    inverseSurface = Color(0xFFECEDE6), inverseOnSurface = Color(0xFF18211E), inversePrimary = Color(0xFF6E5A00),
)

private val Daylight = lightColorScheme(
    primary = Color(0xFF6B5800), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E08A), onPrimaryContainer = Color(0xFF221B00),
    secondary = Color(0xFF256A50), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFEBD5), onSecondaryContainer = Color(0xFF002116),
    tertiary = Color(0xFF9B3B3B), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD7), onTertiaryContainer = Color(0xFF3F0306),
    background = Color(0xFFF1ECE2), onBackground = Color(0xFF1B2420),
    surface = Color(0xFFF1ECE2), onSurface = Color(0xFF1B2420),
    surfaceVariant = Color(0xFFE2DCCF), onSurfaceVariant = Color(0xFF48524C),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F3EA),
    surfaceContainer = Color(0xFFEDE7DB), surfaceContainerHigh = Color(0xFFE7E1D4), surfaceContainerHighest = Color(0xFFE1DACC),
    outline = Color(0xFF78827B), outlineVariant = Color(0xFFC9C3B6),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
)

val Chalk = FontFamily(Font(R.font.cabin_sketch_bold, FontWeight.Bold))

@Immutable
data class Accents(val saving: Color, val struck: Color, val ok: Color, val warn: Color)
val LocalAccents = staticCompositionLocalOf { Accents(Color.Yellow, Color.Red, Color.Green, Color(0xFFFFB74D)) }

/** Money is written in chalk; size steps on a fixed ramp by how much is saved. */
fun moneyStyle(saving: Double): TextStyle {
    val size = when {
        saving >= 10 -> 30.sp
        saving >= 5 -> 26.sp
        saving >= 2 -> 23.sp
        else -> 21.sp
    }
    return TextStyle(fontFamily = Chalk, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.05)
}

@Composable
fun FoodFinderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Board else Daylight
    val accents = if (dark) Accents(Color(0xFFF2D46B), Color(0xFFF2A3A3), Color(0xFF8FD3B4), Color(0xFFF5C27A))
    else Accents(Color(0xFF6B5800), Color(0xFF9B3B3B), Color(0xFF256A50), Color(0xFF8A5300))
    val base = Typography()
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Chalk, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Chalk, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Chalk, fontWeight = FontWeight.Bold),
    )
    CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
