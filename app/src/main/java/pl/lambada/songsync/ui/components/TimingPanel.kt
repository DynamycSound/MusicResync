package pl.lambada.songsync.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Offsets snap to this step while dragging; the −/+ buttons move twice as far. */
private const val SNAP_MS = 50f
private const val STEP_MS = 100f

private fun snap(value: Float, rangeMs: Float) = ((value / SNAP_MS).roundToInt() * SNAP_MS).coerceIn(-rangeMs, rangeMs)

/** The reset button for a timing [PopupPanel]'s header; only clickable when there is an offset to undo. */
@Composable
fun TimingResetButton(offsetMs: Float, onReset: () -> Unit) {
    IconButton(onClick = onReset, enabled = offsetMs != 0f) {
        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.timing_reset))
    }
}

/**
 * Timing controls for lyrics: positive [offsetMs] shows each line sooner. Every change is reported through
 * [onOffsetChange]; [onCommit] fires when a drag ends or a button is pressed, for saving or re-seeking.
 * [accent] colours the slider (the live view passes the artwork's colour).
 */
@Composable
fun ColumnScope.TimingPanel(
    offsetMs: Float,
    rangeMs: Float,
    onOffsetChange: (Float) -> Unit,
    onCommit: () -> Unit,
    hint: String = stringResource(R.string.timing_hint),
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    fun set(value: Float) = onOffsetChange(snap(value, rangeMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FilledTonalIconButton(onClick = { set(offsetMs - STEP_MS); onCommit() }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.timing_later))
        }
        AnimatedContent(
            targetState = formatOffsetSeconds(offsetMs),
            transitionSpec = {
                // Roll the number up when it grows and down when it shrinks.
                val up = initialState.parseOffset() < targetState.parseOffset()
                (slideInVertically { if (up) it / 2 else -it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { if (up) -it / 2 else it / 2 } + fadeOut())
            },
            label = "offsetValue",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (offsetMs == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        FilledTonalIconButton(onClick = { set(offsetMs + STEP_MS); onCommit() }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.timing_sooner))
        }
    }
    OffsetSlider(
        value = offsetMs,
        rangeMs = rangeMs,
        onValueChange = ::set,
        onValueChangeFinished = onCommit,
        accent = accent,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.timing_later), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.timing_sooner), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

private fun String.parseOffset(): Float = removeSuffix("s").toFloatOrNull() ?: 0f

/**
 * A centred slider for an offset: a thin track with a tick every second and a taller notch at zero, a glowing bar
 * from the centre to the value, and the same ringed thumb as [WaveScrubber]. Gives a light tick when the value
 * crosses zero, so "back to in sync" can be found by feel.
 */
@Composable
private fun OffsetSlider(
    value: Float,
    rangeMs: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val current by rememberUpdatedState(value)
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (dragging) 1.25f else 1f, label = "offsetThumb")
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val description = stringResource(R.string.timing_title)

    fun valueAt(x: Float, width: Float, inset: Float): Float {
        val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return -rangeMs + 2 * rangeMs * fraction
    }

    fun move(target: Float) {
        val snapped = snap(target, rangeMs)
        // Landing on zero, or jumping straight across it.
        if (snapped != current && (snapped == 0f || snapped.sign * current.sign < 0f)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        change(snapped)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value, -rangeMs..rangeMs)
                setProgress { target -> change(snap(target, rangeMs)); finished(); true }
            }
            .pointerInput(rangeMs) {
                val inset = 12.dp.toPx()
                detectTapGestures { offset ->
                    move(valueAt(offset.x, size.width.toFloat(), inset))
                    finished()
                }
            }
            .pointerInput(rangeMs) {
                val inset = 12.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        move(valueAt(offset.x, size.width.toFloat(), inset))
                    },
                    onDragEnd = { dragging = false; finished() },
                    onDragCancel = { dragging = false; finished() },
                ) { pointer, _ ->
                    pointer.consume()
                    move(valueAt(pointer.position.x, size.width.toFloat(), inset))
                }
            }
    ) {
        val inset = 12.dp.toPx()
        val left = inset
        val right = size.width - inset
        val mid = size.height / 2f
        val centerX = (left + right) / 2f
        val fraction = ((value + rangeMs) / (2 * rangeMs)).coerceIn(0f, 1f)
        val thumbX = left + (right - left) * fraction

        drawLine(trackColor, Offset(left, mid), Offset(right, mid), 4.dp.toPx(), StrokeCap.Round)

        // A tick every second, a taller notch at zero.
        val seconds = (rangeMs / 1000f).toInt()
        for (s in -seconds..seconds) {
            val x = left + (right - left) * ((s * 1000f + rangeMs) / (2 * rangeMs))
            val half = if (s == 0) 9.dp.toPx() else 4.dp.toPx()
            drawLine(
                color = if (s == 0) tickColor.copy(alpha = 0.8f) else tickColor,
                start = Offset(x, mid - half),
                end = Offset(x, mid + half),
                strokeWidth = if (s == 0) 2.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // The bar from zero to the value, brightest at the thumb.
        if (abs(thumbX - centerX) > 1f) {
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to accent.copy(alpha = 0.35f),
                    1f to accent,
                    startX = centerX,
                    endX = thumbX,
                ),
                start = Offset(centerX, mid),
                end = Offset(thumbX, mid),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val radius = 9.dp.toPx() * thumbScale
        val glow = radius * 2.4f
        val center = Offset(thumbX, mid)
        drawCircle(
            brush = Brush.radialGradient(
                0f to accent.copy(alpha = 0.5f),
                0.5f to accent.copy(alpha = 0.18f),
                1f to Color.Transparent,
                center = center,
                radius = glow,
            ),
            radius = glow,
            center = center,
        )
        drawCircle(Color.White, radius = radius, center = center)
        drawCircle(accent, radius = radius - 2.5.dp.toPx(), center = center)
    }
}
