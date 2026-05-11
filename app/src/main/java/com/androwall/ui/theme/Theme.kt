package com.androwall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CyberColorScheme = darkColorScheme(
    primary              = NeonCyan,
    onPrimary            = CyberVoid,
    primaryContainer     = NeonCyanGhost,
    onPrimaryContainer   = NeonCyan,
    secondary            = NeonGreen,
    onSecondary          = CyberVoid,
    secondaryContainer   = NeonGreenGhost,
    onSecondaryContainer = NeonGreen,
    tertiary             = NeonPurple,
    onTertiary           = CyberVoid,
    tertiaryContainer    = NeonPurpleGhost,
    onTertiaryContainer  = NeonPurple,
    error                = NeonRed,
    onError              = CyberVoid,
    errorContainer       = NeonRedGhost,
    onErrorContainer     = NeonRed,
    background           = CyberBlack,
    onBackground         = CyberTextPrimary,
    surface              = CyberDeep,
    onSurface            = CyberTextPrimary,
    surfaceVariant       = CyberNavy,
    onSurfaceVariant     = CyberTextSecondary,
    outline              = CyberBorderMid,
    outlineVariant       = CyberBorderFaint,
    inverseSurface       = CyberTextPrimary,
    inverseOnSurface     = CyberVoid,
    inversePrimary       = NeonCyanDim,
    scrim                = CyberVoid
)

@Composable
fun AndroWallTheme(content: @Composable () -> Unit) {
    // Always dark — no dynamic color, no light mode
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography  = CyberTypography,
        shapes      = CyberShapes,
        content     = content
    )
}