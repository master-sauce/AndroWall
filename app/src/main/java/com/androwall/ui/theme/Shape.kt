package com.androwall.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val CyberShapes = Shapes(
    // Small chips, badges
    extraSmall = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
    // Buttons, text fields
    small      = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
    // Cards
    medium     = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    // Dialogs
    large      = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
    // Bottom sheet / big surfaces
    extraLarge = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
)

// Convenience aliases used directly in composables
val CyberShapeSmall   = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
val CyberShapeMedium  = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
val CyberShapeLarge   = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
val CyberShapeChip    = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)