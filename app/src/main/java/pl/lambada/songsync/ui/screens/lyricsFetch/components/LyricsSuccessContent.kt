package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R

// Line tags "[...]" AND enhanced-LRC word stamps "<00:40.49>" — neither belongs in a text preview.
private val lrcTag = Regex("""\[[^]]*]|<\d{1,2}:\d{2}[.:]\d{1,3}>""")

/**
 * Result-screen lyric panel. The heavy lifting (play-along preview + offset correction) now lives in the
 * full-screen synced player, reached via "Adjust timing" — so here we keep it light: a clean text preview plus
 * Save / Embed actions that flip to a green "saved" state until a new result arrives.
 *
 * @param onAdjustTiming saves the .lrc and opens the player; null for non-local sources (no file to play).
 */
@Composable
fun LyricsSuccessContent(
    lyrics: String,
    onSaveLyrics: () -> Unit,
    onEmbedLyrics: () -> Unit,
    onAdjustTiming: (() -> Unit)? = null,
    /** Fetches cover candidates for the thumbnail picker; null for non-local sources (no file to write to). */
    onRequestCovers: (suspend () -> List<String>)? = null,
    onPickCover: (String) -> Unit = {},
    hasCover: Boolean? = null,
) {
    // Reset the "saved" chips whenever a different result is shown.
    var savedLrc by remember(lyrics) { mutableStateOf(false) }
    var embedded by remember(lyrics) { mutableStateOf(false) }
    var showThumbnailPicker by remember { mutableStateOf(false) }

    Column {
        if (onAdjustTiming != null) {
            Button(onClick = onAdjustTiming, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.adjust_timing_preview))
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SaveButton(
                saved = savedLrc,
                idleText = stringResource(R.string.save_lrc_file),
                savedText = stringResource(R.string.saved_as_lrc),
                onClick = { onSaveLyrics(); savedLrc = true },
                modifier = Modifier.weight(1f),
            )
            SaveButton(
                saved = embedded,
                idleText = stringResource(R.string.embed_lyrics_in_file),
                savedText = stringResource(R.string.embedded_lyrics),
                onClick = { onEmbedLyrics(); embedded = true },
                modifier = Modifier.weight(1f),
            )
        }

        // Thumbnail picker: lets the user attach album art to the file (label reflects whether one exists).
        if (onRequestCovers != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showThumbnailPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (hasCover == false) R.string.add_thumbnail else R.string.change_thumbnail))
            }
            if (showThumbnailPicker) {
                ThumbnailPickerDialog(
                    onRequestCovers = onRequestCovers,
                    onPick = onPickCover,
                    onDismiss = { showThumbnailPicker = false },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Compact, timestamp-free preview so the user can confirm it's the right lyrics; the play-along
        // showcase is the player itself.
        val previewLines = lyrics.lineSequence()
            .map { lrcTag.replace(it, "").trim() }
            .filter { it.isNotBlank() }
            .toList()
        OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            SelectionContainer {
                Text(
                    text = previewLines.take(8).joinToString("\n") +
                        if (previewLines.size > 8) "\n…" else "",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Save/Embed action with animated success feedback: on save the button springs (a quick overshoot pulse),
 * morphs to green, and the checkmark pops in while the label crossfades+slides to the "saved" text — a clear,
 * satisfying confirmation instead of an instant text swap.
 */
@Composable
private fun SaveButton(
    saved: Boolean,
    idleText: String,
    savedText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (saved) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(350),
        label = "saveBtnColor",
    )
    // Whole-button pulse: a springy overshoot on the transition into "saved".
    val pulse by animateFloatAsState(
        targetValue = if (saved) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "saveBtnPulse",
    )
    val buttonScale = 1f + 0.06f * kotlin.math.sin(pulse * Math.PI).toFloat()

    Button(
        onClick = onClick,
        modifier = modifier.graphicsLayer { scaleX = buttonScale; scaleY = buttonScale },
        colors = ButtonDefaults.buttonColors(containerColor = container),
    ) {
        AnimatedContent(
            targetState = saved,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 2 })
            },
            label = "saveBtnLabel",
        ) { isSaved ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSaved) {
                    // The check pops in with a spring once the content has switched.
                    val checkScale = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        checkScale.animateTo(
                            1f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        )
                    }
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { scaleX = checkScale.value; scaleY = checkScale.value },
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isSaved) savedText else idleText, maxLines = 1)
            }
        }
    }
}
