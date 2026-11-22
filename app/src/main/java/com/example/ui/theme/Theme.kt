package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode(val label: String) {
    DARK("Obsidian Dark"),
    LIGHT("Frosted Light"),
    SYSTEM("System Auto")
}

private val PulseDarkColorScheme = darkColorScheme(
    primary = DarkSandPrimary,
    onPrimary = ObsidianBg,
    primaryContainer = DarkSlateContainer,
    onPrimaryContainer = Color.White,
    secondary = DarkTaupeSecondary,
    onSecondary = ObsidianBg,
    secondaryContainer = ObsidianSurfaceVariant,
    onSecondaryContainer = Color.White,
    tertiary = CoralWave,
    onTertiary = Color.White,
    background = ObsidianBg,
    onBackground = ObsidianTextPrimary,
    surface = ObsidianSurface,
    onSurface = ObsidianTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ObsidianTextSecondary,
    outline = ObsidianBorder,
    error = ErrorRed,
    onError = Color.White
)

private val PulseLightColorScheme = lightColorScheme(
    primary = MintLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F7EE),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = UltravioletLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF3B0764),
    tertiary = CoralLightTertiary,
    onTertiary = Color.White,
    background = FrostedBg,
    onBackground = FrostedTextPrimary,
    surface = FrostedSurface,
    onSurface = FrostedTextPrimary,
    surfaceVariant = FrostedSurfaceVariant,
    onSurfaceVariant = FrostedTextSecondary,
    outline = FrostedBorder,
    error = ErrorRed,
    onError = Color.White
)

val PulseWaveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PulseWaveTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    useDynamicSystemColor: Boolean = false,
    albumAccentOverride: Color? = null,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val baseScheme = when {
        useDynamicSystemColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> PulseDarkColorScheme
        else -> PulseLightColorScheme
    }

    val animatedAccent = albumAccentOverride?.let { target ->
        androidx.compose.animation.animateColorAsState(
            targetValue = target,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 550),
            label = "album_accent_anim"
        ).value
    }

    val colorScheme = if (animatedAccent != null) {
        baseScheme.copy(
            primary = animatedAccent,
            tertiary = baseScheme.tertiary
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseWaveTypography,
        shapes = PulseWaveShapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}
