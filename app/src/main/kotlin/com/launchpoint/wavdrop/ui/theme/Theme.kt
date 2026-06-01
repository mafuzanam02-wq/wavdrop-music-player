package com.launchpoint.wavdrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary          = PrimaryViolet,
    primaryContainer = PrimaryVioletContainer,
    onPrimary        = OnPrimaryViolet,
    secondary        = SecondaryAqua,
    background       = BackgroundDark,
    surface          = SurfaceDark,
    surfaceVariant   = SurfaceVariantDark,
    onSurface        = OnSurfaceDark,
    onBackground     = OnSurfaceDark,
)

private val LightColors = lightColorScheme(
    primary        = PrimaryViolet,
    secondary      = SecondaryAqua,
    background     = BackgroundLight,
    surface        = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurface      = OnSurfaceLight,
    onBackground   = OnSurfaceLight,
)

@Composable
fun WavdropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = WavdropTypography,
        content     = content,
    )
}
