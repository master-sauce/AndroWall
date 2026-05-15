package com.androwall.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Pulsing status dot ────────────────────────────────────────────────────────

@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        "dotAlpha"
    )
    Canvas(modifier.size(size)) {
        val r = size.toPx() / 2
        drawCircle(color.copy(0.15f), r * 2.2f)   // static outer ring
        drawCircle(color.copy(alpha),  r * 0.72f)  // pulsing core
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
fun PhoenixSectionHeader(text: String, color: Color = PhoenixFlame, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Text(
            text,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 12.sp,
            color      = color
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(color, color.copy(0.15f)))
                )
        )
    }
}

// ── Status chip ───────────────────────────────────────────────────────────────

@Composable
fun EmberChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(0.12f), PhoenixShapeChip)
            .border(0.5.dp, color.copy(0.55f), PhoenixShapeChip)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontWeight    = FontWeight.SemiBold,
            fontSize      = 10.sp,
            letterSpacing = 0.3.sp,
            color         = color
        )
    }
}

// ── Alert toast card ──────────────────────────────────────────────────────────

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
                .border(1.dp, color.copy(0.65f), PhoenixShapeMedium)
                .clip(PhoenixShapeMedium)
        ) {
            // Accent strip
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopStart)
                    .background(
                        Brush.horizontalGradient(listOf(color, color.copy(0.3f), Color.Transparent))
                    )
            )
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(color.copy(0.12f), PhoenixShapeSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Shield Alert", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
                    Spacer(Modifier.height(2.dp))
                    Text(message, fontSize = 12.sp, lineHeight = 17.sp, color = color.copy(0.85f))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(28.dp)
                        .background(color.copy(0.1f), PhoenixShapeChip)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close, "Dismiss",
                        tint     = color.copy(0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}