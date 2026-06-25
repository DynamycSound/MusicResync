package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R

private val lrcTag = Regex("""\[[^]]*]""")

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
        label = "saveBtn",
    )
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = container),
    ) {
        if (saved) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(if (saved) savedText else idleText, maxLines = 1)
    }
}
