package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val StreamDarkColorScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainer,
    onPrimaryContainer = OnCrimsonContainer,
    secondary = CoralSecondary,
    onSecondary = Color.White,
    secondaryContainer = CoralContainer,
    onSecondaryContainer = OnCoralContainer,
    tertiary = CyanAccent,
    onTertiary = Color(0xFF042026),
    tertiaryContainer = CyanContainer,
    onTertiaryContainer = OnCyanContainer,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceHigh,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceHigh,
    surfaceContainerHigh = SlateSurfaceHighest,
    outline = SlateBorder,
    outlineVariant = Color(0xFF1F2737),
    error = Color(0xFFFF5370),
    onError = Color.White
)

private val StreamLightColorScheme = lightColorScheme(
    primary = CrimsonPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainerLight,
    onPrimaryContainer = OnCrimsonContainerLight,
    secondary = CoralSecondary,
    onSecondary = Color.White,
    tertiary = Color(0xFF0891B2),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceHigh,
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = LightBorder,
    outlineVariant = Color(0xFFE8ECF4),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val StreamShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StreamTubeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) StreamDarkColorScheme else StreamLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StreamTubeTypography,
        shapes = StreamShapes,
        content = content
    )
}
