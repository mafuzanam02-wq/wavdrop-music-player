package com.launchpoint.wavdrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.ThemeMode

// Midnight Violet light-mode container (complement to PrimaryVioletContainer)
private val MidnightVioletContainerLight  = Color(0xFFEDE9FE)
private val MidnightVioletOnContainerDark  = Color(0xFFE6D9FF)
private val MidnightVioletOnContainerLight = Color(0xFF3D1F9E)

private data class PrimaryColors(
    val primary: Color,
    val containerDark: Color,
    val containerLight: Color,
    val onPrimary: Color,
    val onContainerDark: Color,
    val onContainerLight: Color,
)

private fun AccentColor.toPrimaryColors() = when (this) {
    AccentColor.MIDNIGHT_VIOLET -> PrimaryColors(
        primary           = PrimaryViolet,
        containerDark     = PrimaryVioletContainer,
        containerLight    = MidnightVioletContainerLight,
        onPrimary         = OnPrimaryViolet,
        onContainerDark   = MidnightVioletOnContainerDark,
        onContainerLight  = MidnightVioletOnContainerLight,
    )
    AccentColor.CLEAN_PURPLE -> PrimaryColors(
        primary           = CleanPurplePrimary,
        containerDark     = CleanPurpleContainerDark,
        containerLight    = CleanPurpleContainerLight,
        onPrimary         = Color.White,
        onContainerDark   = CleanPurpleOnContainerDark,
        onContainerLight  = CleanPurpleOnContainerLight,
    )
    AccentColor.DEEP_TEAL -> PrimaryColors(
        primary           = DeepTealPrimary,
        containerDark     = DeepTealContainerDark,
        containerLight    = DeepTealContainerLight,
        onPrimary         = Color.White,
        onContainerDark   = DeepTealOnContainerDark,
        onContainerLight  = DeepTealOnContainerLight,
    )
}

@Composable
fun WavdropTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.MIDNIGHT_VIOLET,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }

    val pc = accentColor.toPrimaryColors()

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary            = pc.primary,
            primaryContainer   = pc.containerDark,
            onPrimary          = pc.onPrimary,
            onPrimaryContainer = pc.onContainerDark,
            secondary          = SecondaryAqua,
            background         = BackgroundDark,
            surface            = SurfaceDark,
            surfaceVariant     = SurfaceVariantDark,
            onSurface          = OnSurfaceDark,
            onBackground       = OnSurfaceDark,
        )
    } else {
        lightColorScheme(
            primary            = pc.primary,
            primaryContainer   = pc.containerLight,
            onPrimary          = pc.onPrimary,
            onPrimaryContainer = pc.onContainerLight,
            secondary          = SecondaryAqua,
            background         = BackgroundLight,
            surface            = SurfaceLight,
            surfaceVariant     = SurfaceVariantLight,
            onSurface          = OnSurfaceLight,
            onBackground       = OnSurfaceLight,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = WavdropTypography,
        content     = content,
    )
}
