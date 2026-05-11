package com.androwall.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Phoenix shield shapes.
 * Asymmetric cut corners (top-start + bottom-end) simultaneously read as
 * shield facets and flame tips — reinforcing the fire-phoenix-shield identity.
 */
val PhoenixShapes = Shapes(
    extraSmall = CutCornerShape(topStart = 4.dp,  bottomEnd = 4.dp),
    small      = CutCornerShape(topStart = 6.dp,  bottomEnd = 6.dp),
    medium     = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large      = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
)

// Direct-use aliases
val PhoenixShapeChip   = CutCornerShape(topStart = 4.dp,  bottomEnd = 4.dp)
val PhoenixShapeSmall  = CutCornerShape(topStart = 6.dp,  bottomEnd = 6.dp)
val PhoenixShapeMedium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
val PhoenixShapeLarge  = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)