package com.murmur.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MurmurDarkPrimary,
    onPrimary = MurmurDarkOnPrimary,
    secondary = MurmurDarkSecondary,
    tertiary = MurmurDarkTertiary,
    background = MurmurDarkBackground,
    surface = MurmurDarkSurface,
    surfaceVariant = MurmurDarkSurfaceVariant,
    onBackground = MurmurDarkOnBackground,
    onSurface = MurmurDarkOnSurface,
)

private val LightColorScheme = lightColorScheme(
    primary = MurmurLightPrimary,
    onPrimary = MurmurLightOnPrimary,
    secondary = MurmurLightSecondary,
    tertiary = MurmurLightTertiary,
    background = MurmurLightBackground,
    surface = MurmurLightSurface,
    onBackground = MurmurLightOnBackground,
    onSurface = MurmurLightOnSurface,
)

@Composable
fun MurmurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // disabled — we want consistent Murmur branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MurmurTypography,
        content = content
    )
}
