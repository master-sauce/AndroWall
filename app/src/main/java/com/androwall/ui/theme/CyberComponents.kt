package com.androwall.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Neon border modifier ──────────────────────────────────────────────────────

fun Modifier.neonBorder(
    color: Color = NeonCyan,
    width: Dp = 1.dp,
    cutCorner: Dp = 10.dp
): Modifier = this.border(width, color.copy(alpha = 0.6f), CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner))

fun Modifier.cyberBackground(
    base: Color = CyberNavy,
    cutCorner: Dp = 10.dp
): Modifier = this.background(base, CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner))

// ── Animated scan line overlay ─────────────────────────────────────────────────

@Composable
fun ScanLineOverlay(modifier: Modifier = Modifier, color: Color = NeonCyan) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "scanY"
    )
    Canvas(modifier) {
        val y = size.height * scanY
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, color.copy(0.15f), color.copy(0.4f), color.copy(0.15f), Color.Transparent)
            ),
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Faded trail
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, color.copy(0.05f), color.copy(0.1f), color.copy(0.05f), Color.Transparent)
            ),
            start = Offset(0f, y + 6.dp.toPx()),
            end   = Offset(size.width, y + 6.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }
}

// ── Grid background ────────────────────────────────────────────────────────────

@Composable
fun CyberGrid(modifier: Modifier = Modifier, color: Color = NeonCyan, cellSize: Dp = 28.dp) {
    Canvas(modifier) {
        val alpha = 0.04f
        val cell = cellSize.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(color.copy(alpha), Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
            x += cell
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(color.copy(alpha), Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
            y += cell
        }
    }
}

// ── Pulsing glow dot (status indicator) ───────────────────────────────────────

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )
    Canvas(modifier.size(size)) {
        // Outer glow
        drawCircle(color.copy(alpha = 0.15f * alpha), radius = (size.toPx() / 2) * scale * 1.8f)
        // Core dot
        drawCircle(color.copy(alpha = alpha), radius = (size.toPx() / 2) * scale * 0.7f)
    }
}

// ── Section header (monospace with neon accent line) ──────────────────────────

@Composable
fun CyberSectionHeader(
    text: String,
    color: Color = NeonCyan,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = color
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(0.8f), color.copy(0.2f), Color.Transparent)
                    )
                )
        )
    }
}

// ── Neon chip ─────────────────────────────────────────────────────────────────

@Composable
fun NeonChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(color.copy(alpha = 0.1f), CyberShapeChip)
            .border(0.5.dp, color.copy(alpha = 0.7f), CyberShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = color
        )
    }
}