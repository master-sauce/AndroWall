package com.androwall.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Neon border / background modifiers ───────────────────────────────────────

fun Modifier.neonBorder(
    color: Color = NeonCyan,
    width: Dp = 1.dp,
    cutCorner: Dp = 10.dp
): Modifier = this.border(width, color.copy(0.6f), CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner))

fun Modifier.cyberBackground(
    base: Color = CyberNavy,
    cutCorner: Dp = 10.dp
): Modifier = this.background(base, CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner))

// ── Animated scan line ────────────────────────────────────────────────────────

@Composable
fun ScanLineOverlay(modifier: Modifier = Modifier, color: Color = NeonCyan) {
    val inf = rememberInfiniteTransition(label = "scan")
    val y by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "scanY"
    )
    Canvas(modifier) {
        val yPx = size.height * y
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, color.copy(0.15f), color.copy(0.4f), color.copy(0.15f), Color.Transparent)),
            Offset(0f, yPx), Offset(size.width, yPx), 2.dp.toPx(), StrokeCap.Round
        )
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, color.copy(0.05f), color.copy(0.1f), color.copy(0.05f), Color.Transparent)),
            Offset(0f, yPx + 6.dp.toPx()), Offset(size.width, yPx + 6.dp.toPx()), 1.dp.toPx()
        )
    }
}

// ── Grid background ───────────────────────────────────────────────────────────

@Composable
fun CyberGrid(modifier: Modifier = Modifier, color: Color = NeonCyan, cellSize: Dp = 28.dp) {
    Canvas(modifier) {
        val a = 0.04f; val c = cellSize.toPx()
        var x = 0f; while (x <= size.width)  { drawLine(color.copy(a), Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx()); x += c }
        var y = 0f; while (y <= size.height) { drawLine(color.copy(a), Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx()); y += c }
    }
}

// ── Pulsing dot ───────────────────────────────────────────────────────────────

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val scale by inf.animateFloat(0.7f, 1.3f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), "scale")
    val alpha by inf.animateFloat(0.4f, 1f,   infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), "alpha")
    Canvas(modifier.size(size)) {
        drawCircle(color.copy(0.15f * alpha), (size.toPx() / 2) * scale * 1.8f)
        drawCircle(color.copy(alpha),         (size.toPx() / 2) * scale * 0.7f)
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
fun CyberSectionHeader(text: String, color: Color = NeonCyan, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 2.sp, color = color)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(color.copy(0.8f), color.copy(0.2f), Color.Transparent))))
    }
}

// ── Neon chip ─────────────────────────────────────────────────────────────────

@Composable
fun NeonChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(0.1f), CyberShapeChip)
            .border(0.5.dp, color.copy(0.7f), CyberShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 9.sp, letterSpacing = 1.5.sp, color = color)
    }
}

// ── Card toast (floating error card) ─────────────────────────────────────────
//
//  Usage:
//    Box { ... ; CyberToastCard(message, visible, onDismiss, Modifier.align(Alignment.TopCenter)) }
//
//  Auto-dismisses after 4 seconds. Manual dismiss via ✕ button.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CyberToastCard(
    message: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NeonRed
) {
    // Auto-dismiss timer — cancels automatically if visible flips to false before 4 s
    LaunchedEffect(visible) {
        if (visible) {
            delay(4_000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible  = visible,
        modifier = modifier,
        enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(300)),
        exit  = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(220))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .background(CyberDeep, CyberShapeMedium)
                .border(1.dp, color.copy(0.85f), CyberShapeMedium)
                .clip(CyberShapeMedium)
        ) {
            // Subtle grid overlay
            CyberGrid(Modifier.matchParentSize(), color = color, cellSize = 18.dp)

            // Top neon glow strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(1f), color.copy(0.5f), Color.Transparent)
                        )
                    )
            )

            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon box
                Box(
                    Modifier
                        .size(34.dp)
                        .background(color.copy(0.12f), CyberShapeSmall)
                        .border(0.5.dp, color.copy(0.55f), CyberShapeSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(17.dp))
                }

                Spacer(Modifier.width(12.dp))

                // Text block
                Column(Modifier.weight(1f)) {
                    Text(
                        "SYSTEM  ERROR",
                        fontFamily    = FontFamily.Monospace,
                        fontWeight    = FontWeight.Black,
                        fontSize      = 9.sp,
                        letterSpacing = 3.sp,
                        color         = color
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        message,
                        fontFamily    = FontFamily.Monospace,
                        fontSize      = 11.sp,
                        letterSpacing = 0.3.sp,
                        lineHeight    = 16.sp,
                        color         = color.copy(0.85f)
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Dismiss button
                Box(
                    Modifier
                        .size(26.dp)
                        .background(color.copy(0.1f), CyberShapeChip)
                        .border(0.5.dp, color.copy(0.45f), CyberShapeChip)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Dismiss", tint = color.copy(0.85f), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}