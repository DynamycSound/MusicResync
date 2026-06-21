package pl.lambada.songsync.ui.screens.home.components.batchDownload

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.SwitchItem

@Composable
fun BatchDownloadWarningDialog(
    songsToProcess: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    saveLrc: Boolean,
    onSaveLrcChangeRequest: (Boolean) -> Unit,
    embedLyrics: Boolean,
    onEmbedLyricsChangeRequest: (Boolean) -> Unit,
    correctMetadata: Boolean,
    onCorrectMetadataChangeRequest: (Boolean) -> Unit,
    skipExisting: Boolean,
    onSkipExistingChangeRequest: (Boolean) -> Unit,
    autoTryProviders: Boolean,
    onAutoTryProvidersChangeRequest: (Boolean) -> Unit,
) {
    val rowPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
    var advancedExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        title = { Text(text = stringResource(id = R.string.batch_download_lyrics)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (songsToProcess == 0)
                        stringResource(R.string.all_songs_have_lyrics)
                    else
                        stringResource(R.string.will_fetch_for_n_songs, songsToProcess)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // The two choices that matter to almost everyone stay up top; everything else is tucked
                // away so a first-time user can just tap "Yes".
                SwitchItem(
                    label = stringResource(R.string.skip_existing_lyrics),
                    description = stringResource(R.string.skip_existing_lyrics_desc),
                    selected = skipExisting,
                    modifier = Modifier,
                    innerPaddingValues = rowPadding,
                ) { onSkipExistingChangeRequest(!skipExisting) }

                SwitchItem(
                    label = stringResource(R.string.save_lrc_next_to_song),
                    description = stringResource(R.string.save_lrc_desc),
                    selected = saveLrc,
                    modifier = Modifier,
                    innerPaddingValues = rowPadding,
                ) { onSaveLrcChangeRequest(!saveLrc) }

                // More options (collapsed by default)
                Row(
                    modifier = Modifier
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.more_options),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(visible = advancedExpanded) {
                    Column {
                        SwitchItem(
                            label = stringResource(R.string.embed_lyrics_in_file),
                            description = stringResource(R.string.embed_lyrics_desc),
                            selected = embedLyrics,
                            modifier = Modifier,
                            innerPaddingValues = rowPadding,
                        ) { onEmbedLyricsChangeRequest(!embedLyrics) }

                        SwitchItem(
                            label = stringResource(R.string.correct_metadata),
                            description = stringResource(R.string.correct_metadata_desc),
                            selected = correctMetadata,
                            modifier = Modifier,
                            innerPaddingValues = rowPadding,
                        ) { onCorrectMetadataChangeRequest(!correctMetadata) }

                        SwitchItem(
                            label = stringResource(R.string.auto_try_providers),
                            description = stringResource(R.string.auto_try_providers_desc),
                            selected = autoTryProviders,
                            modifier = Modifier,
                            innerPaddingValues = rowPadding,
                        ) { onAutoTryProvidersChangeRequest(!autoTryProviders) }

                        Text(
                            text = stringResource(R.string.private_lrc_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onConfirm, enabled = songsToProcess > 0) {
                Text(text = stringResource(R.string.yes))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.no))
            }
        }
    )
}
