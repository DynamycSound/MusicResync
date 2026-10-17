package pl.lambada.songsync.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * One translucent wave riding on the played part of the track. [speed] is in radians per second, so layers drift at
 * different rates (and directions) and the pattern never visibly repeats.
 */
private data class WaveLayer(val wavelengthDp: Float, val heightDp: Float, val speed: Float, val shift: Float, val alpha: Float)

private val layers = listOf(
    WaveLayer(wavelengthDp = 120f, heightDp = 26f, speed = 2.3f, shift = 0f, alpha = 0.9f),
    WaveLayer(wavelengthDp = 78f, heightDp = 19f, speed = -3.1f, shift = 1.7f, alpha = 0.8f),
    WaveLayer(wavelengthDp = 160f, heightDp = 15f, speed = 1.6f, shift = 3.1f, alpha = 0.6f),
)

/**
 * A song position scrubber in the style of current Android media controls: a thin rounded track, soft gradient waves
 * that keep flowing along the played part while [playing] (and calm down when paused), and a ringed thumb with a glow.
 * The waves are drawn in [activeColor] (saturated), [secondaryColor] (a softer tone) and a pale, see-through tint.
 * Tap or drag anywhere on it to move; [onValueChangeFinished] fires when the finger lifts.
 */
@Composable
fun WaveScrubber(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    ringColor: Color = Color.White,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    var dragging by remember { mutableStateOf(false) }

    val energy by animateFloatAsState(if (playing) 1f else 0.2f, tween(800), label = "waveEnergy")
    val thumbScale by animateFloatAsState(if (dragging) 1.25f else 1f, label = "thumbScale")

    // Continuous wave clock (seconds). Only the drawing reads it, so each frame redraws without recomposing; it runs
    // slower while paused instead of snapping back, so the waves never jump.
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { now ->
                clock.floatValue += (now - last) / 1000f * (0.25f + 0.75f * energy)
                last = now
            }
        }
    }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-3f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                if (enabled) setProgress { target ->
                    change(target.coerceIn(valueRange.start, valueRange.endInclusive))
                    finished?.invoke()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    change(valueAt(offset.x, size.width.toFloat(), 12.dp.toPx(), range))
                    finished?.invoke()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val inset = 12.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        change(valueAt(offset.x, size.width.toFloat(), inset, range))
                    },
                    onDragEnd = { dragging = false; finished?.invoke() },
                    onDragCancel = { dragging = false; finished?.invoke() },
                ) { pointer, _ ->
                    pointer.consume()
                    change(valueAt(pointer.position.x, size.width.toFloat(), inset, range))
                }
            }
    ) {
        val t = clock.floatValue
        val inset = 12.dp.toPx()
        val track = 4.dp.toPx()
        val baseline = size.height * 0.74f
        val left = inset
        val right = size.width - inset
        val thumbX = left + (right - left) * fraction
        val layerColors = listOf(activeColor, secondaryColor, lerp(activeColor, Color.White, 0.55f))

        // Played part: waves first, the solid line over their base.
        val waveEnd = thumbX - 6.dp.toPx()
        if (waveEnd > left) {
            val taperIn = 28.dp.toPx()
            val taperOut = 22.dp.toPx()
            val step = 2.dp.toPx()
            layers.forEachIndexed { index, layer ->
                val wavelength = layer.wavelengthDp.dp.toPx()
                val maxHeight = layer.heightDp.dp.toPx() * (0.25f + 0.75f * energy)
                val color = layerColors[index]
                val path = Path().apply {
                    moveTo(left, baseline)
                    var x = left
                    while (x <= waveEnd) {
                        // Fade in from the start and out before the thumb; a slower travelling swell makes the humps
                        // rise and fall as they move.
                        val envelope = smooth(((x - left) / taperIn).coerceIn(0f, 1f)) *
                            smooth(((waveEnd - x) / taperOut).coerceIn(0f, 1f))
                        val swell = 0.55f + 0.45f * sin(x / (wavelength * 2.3f) - t * 0.9f + layer.shift)
                        val wave = (0.3f + 0.7f * sin((x / wavelength) * 2f * PI.toFloat() - t * layer.speed + layer.shift))
                            .coerceAtLeast(0f)
                        lineTo(x, baseline - maxHeight * envelope * swell * wave)
                        x += step
                    }
                    lineTo(waveEnd, baseline)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        0f to color.copy(alpha = layer.alpha),
                        1f to color.copy(alpha = layer.alpha * 0.45f),
                        startY = baseline - layer.heightDp.dp.toPx(),
                        endY = baseline,
                    ),
                )
            }
        }

        // Unplayed part, with a small gap after the thumb.
        val restStart = thumbX + 12.dp.toPx()
        if (restStart < right) {
            drawLine(inactiveColor, Offset(restStart, baseline), Offset(right, baseline), track, StrokeCap.Round)
        }
        if (thumbX > left) {
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to secondaryColor,
                    1f to activeColor,
                    startX = left,
                    endX = thumbX,
                ),
                start = Offset(left, baseline),
                end = Offset(thumbX, baseline),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
        }

        // Thumb: a glow that breathes with the music, a white ring, the accent dot.
        val radius = 9.dp.toPx() * thumbScale
        val glow = radius * (2.3f + 0.5f * energy * (0.5f + 0.5f * sin(t * 3.2f)))
        val center = Offset(thumbX, baseline)
        drawCircle(
            brush = Brush.radialGradient(
                0f to activeColor.copy(alpha = 0.6f),
                0.5f to activeColor.copy(alpha = 0.22f),
                1f to Color.Transparent,
                center = center,
                radius = glow,
            ),
            radius = glow,
            center = center,
        )
        drawCircle(ringColor, radius = radius, center = center)
        drawCircle(activeColor, radius = radius - 2.5.dp.toPx(), center = center)
    }
}

private fun smooth(t: Float) = t * t * (3f - 2f * t)

private fun valueAt(x: Float, width: Float, inset: Float, range: ClosedFloatingPointRange<Float>): Float {
    val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return range.start + (range.endInclusive - range.start) * fraction
}
