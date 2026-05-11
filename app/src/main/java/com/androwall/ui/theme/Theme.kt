package com.androwall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PhoenixColorScheme = darkColorScheme(
    primary              = PhoenixFlame,
    onPrimary            = VoidBlack,
    primaryContainer     = PhoenixFlameGhost,
    onPrimaryContainer   = PhoenixFlame,
    secondary            = EmberAmber,
    onSecondary          = VoidBlack,
    secondaryContainer   = EmberAmberGhost,
    onSecondaryContainer = EmberAmber,
    tertiary             = PhoenixGold,
    onTertiary           = VoidBlack,
    tertiaryContainer    = PhoenixGoldGhost,
    onTertiaryContainer  = PhoenixGold,
    error                = EmberRed,
    onError              = VoidBlack,
    errorContainer       = EmberRedGhost,
    onErrorContainer     = EmberRed,
    background           = AshBlack,
    onBackground         = AshTextPrimary,
    surface              = AshDeep,
    onSurface            = AshTextPrimary,
    surfaceVariant       = AshNavy,
    onSurfaceVariant     = AshTextSecondary,
    outline              = EmberBorderMid,
    outlineVariant       = EmberBorderFaint,
    inverseSurface       = AshTextPrimary,
    inverseOnSurface     = VoidBlack,
    inversePrimary       = PhoenixFlameDim,
    scrim                = VoidBlack
)

@Composable
fun AndroWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhoenixColorScheme,
        typography  = PhoenixTypography,
        shapes      = PhoenixShapes,
        content     = content
    )
}