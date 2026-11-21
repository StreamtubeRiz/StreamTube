package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.roundToInt

data class FlyingDownloadEvent(
    val id: Long = System.currentTimeMillis(),
    val thumbnailUrl: String,
    val startOffset: Offset
)

/**
 * Smooth quadratic-Bezier flying thumbnail animation:
 * Lifts the video thumbnail from the tapped Download button, shrinks it smoothly along
 * a curved path, and lands directly inside the Downloads folder icon.
 */
@Composable
fun DownloadFlightOverlay(
    event: FlyingDownloadEvent?,
    targetOffset: Offset,
    onArrived: () -> Unit,
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (event == null) return

    val progress = remember(event.id) { Animatable(0f) }
    val density = LocalDensity.current
    val halfWidthPx = with(density) { 48.dp.toPx() }
    val halfHeightPx = with(density) { 27.dp.toPx() }

    LaunchedEffect(event.id) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing)
        )
        onArrived()
        onAnimationFinished()
    }

    val t = progress.value
    val start = event.startOffset
    val end = targetOffset
    // Parabolic upward arc control point
    val control = Offset(
        x = (start.x + end.x) / 2f,
        y = minOf(start.y, end.y) - 220f
    )

    // Quadratic Bezier interpolation: B(t) = (1-t)^2*P0 + 2(1-t)t*P1 + t^2*P2
    val oneMinusT = 1f - t
    val currentX = (oneMinusT * oneMinusT * start.x) + (2f * oneMinusT * t * control.x) + (t * t * end.x)
    val currentY = (oneMinusT * oneMinusT * start.y) + (2f * oneMinusT * t * control.y) + (t * t * end.y)

    val scale = 1.05f - (0.82f * t)
    val alpha = if (t > 0.85f) ((1f - t) / 0.15f).coerceIn(0f, 1f) else 1f
    val rotation = t * 14f

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            shadowElevation = 12.dp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (currentX - halfWidthPx).roundToInt(),
                        y = (currentY - halfHeightPx).roundToInt()
                    )
                }
                .scale(scale)
                .rotate(rotation)
                .alpha(alpha)
                .size(width = 96.dp, height = 54.dp)
        ) {
            AsyncImage(
                model = event.thumbnailUrl,
                contentDescription = "Downloading thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }
}
