package com.androwall.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors that vary between dark and light mode.
 * PhoenixFlame / PhoenixFlameDim / PhoenixFlameGhost stay as global constants —
 * the orange brand accent is legible on both backgrounds.
 */
data class AppColors(
    // Backgrounds
    val background   : Color,   // scaffold / screen bg (darkest)
    val surface      : Color,   // top bar / bottom bar / dialog bg
    val card         : Color,   // card / text-field bg
    val cardAlt      : Color,   // icon container / secondary card
    val void         : Color,   // switch thumb (pure black / pure white)
    // Text
    val textPrimary  : Color,
    val textSecondary: Color,
    val textTertiary : Color,
    // Borders
    val borderFaint  : Color,
    val borderMid    : Color,
    val borderBright : Color,
    // Adaptive accents
    val green        : Color,   // allow / success
    val greenGhost   : Color,
    val red          : Color,   // block / error
    val redGhost     : Color,
    val gold         : Color,   // info / warning
    val goldGhost    : Color,
    val isDark       : Boolean
)

val darkAppColors = AppColors(
    background    = AshBlack,
    surface       = AshDeep,
    card          = AshNavy,
    cardAlt       = AshSlate,
    void          = VoidBlack,
    textPrimary   = AshTextPrimary,
    textSecondary = AshTextSecondary,
    textTertiary  = AshTextTertiary,
    borderFaint   = EmberBorderFaint,
    borderMid     = EmberBorderMid,
    borderBright  = EmberBorderBright,
    green         = EmberGreen,
    greenGhost    = EmberGreenGhost,
    red           = EmberRed,
    redGhost      = EmberRedGhost,
    gold          = PhoenixGold,
    goldGhost     = PhoenixGoldGhost,
    isDark        = true
)

val lightAppColors = AppColors(
    background    = LightBackground,
    surface       = LightSurface,
    card          = LightCard,
    cardAlt       = LightCardAlt,
    void          = LightVoid,
    textPrimary   = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textTertiary  = LightTextTertiary,
    borderFaint   = LightBorderFaint,
    borderMid     = LightBorderMid,
    borderBright  = LightBorderBright,
    green         = LightEmberGreen,
    greenGhost    = LightEmberGreenGhost,
    red           = LightEmberRed,
    redGhost      = LightEmberRedGhost,
    gold          = LightPhoenixGold,
    goldGhost     = LightPhoenixGoldGhost,
    isDark        = false
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors }