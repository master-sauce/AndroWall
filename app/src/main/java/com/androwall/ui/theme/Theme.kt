package com.androwall.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Persistent theme preference ───────────────────────────────────────────────

object ThemePreference {
    private const val PREFS = "androwall_prefs"
    private const val KEY   = "dark_mode"

    private val _isDark = MutableStateFlow(true)   // default: dark
    val isDark: StateFlow<Boolean> = _isDark

    /** Call once from MainActivity.onCreate before setContent. */
    fun load(context: Context) {
        _isDark.value = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
    }

    fun toggle(context: Context) = set(context, !_isDark.value)

    fun set(context: Context, dark: Boolean) {
        _isDark.value = dark
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, dark).apply()
    }
}

// ── Color schemes ─────────────────────────────────────────────────────────────

private val PhoenixDarkColorScheme = darkColorScheme(
    primary              = PhoenixFlame,
    onPrimary            = VoidBlack,
    primaryContainer     = PhoenixFlameGhost,
    onPrimaryContainer   = PhoenixFlame,
    secondary            = EmberGreen,
    onSecondary          = VoidBlack,
    secondaryContainer   = EmberGreenGhost,
    onSecondaryContainer = EmberGreen,
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

private val PhoenixLightColorScheme = lightColorScheme(
    primary              = PhoenixFlameDim,
    onPrimary            = LightVoid,
    primaryContainer     = PhoenixFlameGhost,
    onPrimaryContainer   = PhoenixFlameDim,
    secondary            = LightEmberGreen,
    onSecondary          = LightVoid,
    secondaryContainer   = LightEmberGreenGhost,
    onSecondaryContainer = LightEmberGreen,
    tertiary             = LightPhoenixGold,
    onTertiary           = LightVoid,
    tertiaryContainer    = LightPhoenixGoldGhost,
    onTertiaryContainer  = LightPhoenixGold,
    error                = LightEmberRed,
    onError              = LightVoid,
    errorContainer       = LightEmberRedGhost,
    onErrorContainer     = LightEmberRed,
    background           = LightBackground,
    onBackground         = LightTextPrimary,
    surface              = LightSurface,
    onSurface            = LightTextPrimary,
    surfaceVariant       = LightCard,
    onSurfaceVariant     = LightTextSecondary,
    outline              = LightBorderMid,
    outlineVariant       = LightBorderFaint,
    inverseSurface       = LightTextPrimary,
    inverseOnSurface     = LightBackground,
    inversePrimary       = PhoenixFlame,
    scrim                = Color(0x52000000)
)

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun AndroWallTheme(content: @Composable () -> Unit) {
    val isDark by ThemePreference.isDark.collectAsState()

    CompositionLocalProvider(
        LocalAppColors provides if (isDark) darkAppColors else lightAppColors
    ) {
        MaterialTheme(
            colorScheme = if (isDark) PhoenixDarkColorScheme else PhoenixLightColorScheme,
            typography  = PhoenixTypography,
            shapes      = PhoenixShapes,
            content     = content
        )
    }
}