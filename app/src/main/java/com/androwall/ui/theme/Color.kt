package com.androwall.ui.theme

import androidx.compose.ui.graphics.Color

// ── Material baseline (kept for legacy compat) ────────────────────────────────
val Purple80     = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80       = Color(0xFFEFB8C8)
val Purple40     = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40       = Color(0xFF7D5260)

// ── Ash backgrounds (darkest → lightest) ──────────────────────────────────────
val AshBlack  = Color(0xFF0D0B09)   // root scaffold — ash bed
val AshDeep   = Color(0xFF140E06)   // top-bar / bottom-bar / dialogs
val AshNavy   = Color(0xFF1C1510)   // card / surface background
val AshSlate  = Color(0xFF261B12)   // secondary card / icon containers
val VoidBlack = Color(0xFF000000)   // pure black — switch thumb etc

// ── Phoenix Flame — primary brand / action ────────────────────────────────────
val PhoenixFlame      = Color(0xFFFF6B2B)   // vivid fire orange
val PhoenixFlameDim   = Color(0xFFCC4500)   // deeper burnt orange
val PhoenixFlameGhost = Color(0x18FF6B2B)   // ~9 % — nav indicator / ghost fills

// ── Ember Amber — "allow" / active / positive ─────────────────────────────────
val EmberAmber      = Color(0xFFFFAB00)   // molten amber / gold
val EmberAmberGhost = Color(0x18FFAB00)   // ~9 %

// ── Ember Red — "block" / danger / error ──────────────────────────────────────
val EmberRed      = Color(0xFFFF3D00)   // deep fire red
val EmberRedGhost = Color(0x18FF3D00)   // ~9 %

// ── Phoenix Gold — tertiary accent / info ─────────────────────────────────────
val PhoenixGold      = Color(0xFFFFD600)   // molten gold
val PhoenixGoldGhost = Color(0x18FFD600)   // ~9 %

// ── Text tones ────────────────────────────────────────────────────────────────
val AshTextPrimary   = Color(0xFFFFF5E6)   // warm cream — near-white
val AshTextSecondary = Color(0xFF8C7B6B)   // warm grey-brown
val AshTextTertiary  = Color(0xFF4D3D30)   // deep warm brown — subtle hints

// ── Border / divider tones ────────────────────────────────────────────────────
val EmberBorderFaint  = Color(0xFF26180C)
val EmberBorderMid    = Color(0xFF3D2B18)
val EmberBorderBright = Color(0xFF5C4022)