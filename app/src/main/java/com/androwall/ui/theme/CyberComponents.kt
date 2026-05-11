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

// ── Modifier helpers ──────────────────────────────────────────────────────────

fun Modifier.emberBorder(
    color: Color = PhoenixFlame,
    width: Dp = 1.dp,
    cutCorner: Dp = 10.dp
): Modifier = this.border(
    width, color.copy(0.6f),
    CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner)
)

fun Modifier.emberBackground(
    base: Color = AshNavy,
    cutCorner: Dp = 10.dp
): Modifier = this.background(
    base,
    CutCornerShape(topStart = cutCorner, bottomEnd = cutCorner)
)

// ── Flame shimmer line (phoenix rising) ───────────────────────────────────────
// Travels bottom → top, like heat haze or an ember rising off coals.

@Composable
fun FlameLineOverlay(modifier: Modifier = Modifier, color: Color = PhoenixFlame) {
    val inf = rememberInfiniteTransition(label = "flame")
    val y by inf.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "flameY"
    )
    Canvas(modifier) {
        val yPx = size.height * y
        drawLine(
            Brush.horizontalGradient(listOf(
                Color.Transparent, color.copy(0.06f), color.copy(0.32f),
                color.copy(0.48f), color.copy(0.32f), color.copy(0.06f), Color.Transparent
            )),
            Offset(0f, yPx), Offset(size.width, yPx), 3.dp.toPx(), StrokeCap.Round
        )
        // Secondary heat haze ahead of the primary shimmer
        drawLine(
            Brush.horizontalGradient(listOf(
                Color.Transparent, color.copy(0.03f), color.copy(0.10f),
                color.copy(0.03f), Color.Transparent
            )),
            Offset(0f, yPx - 9.dp.toPx()), Offset(size.width, yPx - 9.dp.toPx()), 1.dp.toPx()
        )
    }
}

// ── Ember particle grid ───────────────────────────────────────────────────────
// Fine grid at very low opacity — like a glow pattern cast by embers.

@Composable
fun EmberGrid(modifier: Modifier = Modifier, color: Color = PhoenixFlame, cellSize: Dp = 28.dp) {
    Canvas(modifier) {
        val alpha = 0.032f
        val c = cellSize.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(color.copy(alpha), Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
            x += c
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(color.copy(alpha), Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
            y += c
        }
    }
}

// ── Pulsing ember dot ─────────────────────────────────────────────────────────

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "ember")
    val scale by inf.animateFloat(
        0.6f, 1.4f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "emberScale"
    )
    val alpha by inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "emberAlpha"
    )
    Canvas(modifier.size(size)) {
        val r = size.toPx() / 2
        drawCircle(color.copy(0.10f * alpha), r * scale * 2.4f) // outer halo
        drawCircle(color.copy(0.22f * alpha), r * scale * 1.5f) // mid glow
        drawCircle(color.copy(alpha),          r * scale * 0.65f) // hot core
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
fun PhoenixSectionHeader(text: String, color: Color = PhoenixFlame, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(
            text,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            fontSize      = 10.sp,
            letterSpacing = 2.sp,
            color         = color
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(
                    listOf(color.copy(0.9f), color.copy(0.3f), Color.Transparent)
                )
            )
        )
    }
}

// ── Ember chip ────────────────────────────────────────────────────────────────

@Composable
fun EmberChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(0.12f), PhoenixShapeChip)
            .border(0.5.dp, color.copy(0.75f), PhoenixShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            fontSize      = 9.sp,
            letterSpacing = 1.5.sp,
            color         = color
        )
    }
}

// ── Fire toast card ───────────────────────────────────────────────────────────
//
//  Usage:
//    Box { ..content.. ; FireToastCard(msg, visible, onDismiss, Modifier.align(Alignment.TopCenter)) }
//
//  Auto-dismisses after 4 s. ✕ dismisses immediately.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FireToastCard(
    message: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = EmberRed
) {
    LaunchedEffect(visible) {
        if (visible) { delay(4_000L); onDismiss() }
    }

    AnimatedVisibility(
        visible  = visible,
        modifier = modifier,
        enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(280)),
        exit  = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(200))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .background(AshDeep, PhoenixShapeMedium)
                .border(1.dp, color.copy(0.9f), PhoenixShapeMedium)
                .clip(PhoenixShapeMedium)
        ) {
            EmberGrid(Modifier.matchParentSize(), color = color, cellSize = 20.dp)

            // Top flame glow strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color, color.copy(0.6f), color.copy(0.1f), Color.Transparent)
                        )
                    )
            )

            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(color.copy(0.14f), PhoenixShapeSmall)
                        .border(0.5.dp, color.copy(0.6f), PhoenixShapeSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "SHIELD  ALERT",
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

                Box(
                    Modifier
                        .size(26.dp)
                        .background(color.copy(0.1f), PhoenixShapeChip)
                        .border(0.5.dp, color.copy(0.45f), PhoenixShapeChip)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Dismiss", tint = color.copy(0.85f), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}