package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song

/**
 * Entry in the Filters dialog that opens [HiddenSongsDialog] (issue #18). Styled like the "Ignore folders" row.
 * The caller only shows it when at least one song is hidden, so the dialog is unchanged for everyone else.
 */
@Composable
fun HiddenSongsFilterRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 24.dp, end = 26.dp)
            .clip(RoundedCornerShape(100))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.hidden_songs_count, count))
        }
        IconButton(onClick = onClick) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

/**
 * Lists the songs hidden from the home list, each with an Unhide action (issue #18). [entries] pairs every hidden
 * file path with its song, or null when the file is no longer in the library. Closes itself once nothing is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenSongsDialog(
    entries: List<Pair<String, Song?>>,
    onUnhide: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) onDismiss()
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column {
                Text(
                    text = stringResource(R.string.hidden_songs),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 8.dp)
                )
                Text(
                    text = stringResource(R.string.hidden_songs_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp)
                )
                HorizontalDivider()
                LazyColumn {
                    items(entries, key = { it.first }) { (path, song) ->
                        Row(
                            modifier = Modifier.padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song?.title ?: path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (song != null) song.artist ?: stringResource(R.string.unknown)
                                    else stringResource(R.string.song_not_in_library),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onUnhide(listOf(path)) }) {
                                Text(stringResource(R.string.unhide))
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (entries.size > 1) {
                                TextButton(onClick = { onUnhide(entries.map { it.first }) }) {
                                    Text(stringResource(R.string.unhide_all))
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
            }
        }
    }
}
