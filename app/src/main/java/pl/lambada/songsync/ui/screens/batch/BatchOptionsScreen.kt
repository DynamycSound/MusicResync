package pl.lambada.songsync.ui.screens.batch

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.SettingsHeadLabel
import pl.lambada.songsync.ui.components.SwitchItem
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.ui.screens.home.components.batchDownload.LegacyPromptDialog
import pl.lambada.songsync.util.matching.LyricState

/**
 * Full-screen batch download setup — replaces the old cramped options popup. Shows how many songs the run will
 * process, all the run options as regular settings rows, and a bottom "Start" button that kicks the batch off
 * and moves straight to the full-screen progress view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchOptionsScreen(
    viewModel: HomeViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToProgress: () -> Unit,
) {
    val context = LocalContext.current
    val settings = viewModel.userSettingsController
    val songs = viewModel.songsToBatchDownload
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showLegacyPrompt by remember { mutableStateOf(false) }

    val total = songs.size
    // Songs the batch will actually process when skipExisting is on = those WITHOUT synced lyrics. UNSYNCED
    // (a plain .lrc, no timestamps) is processable too.
    val songsWithoutLyrics = songs.count {
        val st = viewModel.lyricStateFor(it)
        st == LyricState.NO_LYRICS || st == LyricState.FAILED || st == LyricState.UNSYNCED
    }
    val songsToProcess = if (settings.batchSkipExisting) songsWithoutLyrics else total

    fun startAndShowProgress() {
        viewModel.startBatchDownload(context)
        onNavigateToProgress()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.batch_download_lyrics)) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) showLegacyPrompt = true
                        else startAndShowProgress()
                    },
                    enabled = songsToProcess > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // The headline the user cares about: how many songs this run will actually touch.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (songsToProcess == 0)
                            stringResource(R.string.all_songs_have_lyrics)
                        else
                            stringResource(R.string.will_fetch_for_n_songs, songsToProcess),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            SettingsHeadLabel(label = stringResource(R.string.batch_options_general))

            SwitchItem(
                label = stringResource(R.string.skip_existing_lyrics),
                description = stringResource(R.string.skip_existing_lyrics_desc),
                selected = settings.batchSkipExisting,
            ) { settings.updateBatchSkipExisting(!settings.batchSkipExisting) }

            SwitchItem(
                label = stringResource(R.string.save_lrc_next_to_song),
                description = stringResource(R.string.save_lrc_desc),
                selected = settings.batchSaveLrc,
            ) { settings.updateBatchSaveLrc(!settings.batchSaveLrc) }

            SettingsHeadLabel(label = stringResource(R.string.batch_options_advanced))

            SwitchItem(
                label = stringResource(R.string.skip_no_lyrics),
                description = stringResource(R.string.skip_no_lyrics_desc),
                selected = settings.batchSkipNoLyrics,
            ) { settings.updateBatchSkipNoLyrics(!settings.batchSkipNoLyrics) }

            SwitchItem(
                label = stringResource(R.string.embed_lyrics_in_file),
                description = stringResource(R.string.embed_lyrics_desc),
                selected = settings.batchEmbedLyrics,
            ) { settings.updateBatchEmbedLyrics(!settings.batchEmbedLyrics) }

            SwitchItem(
                label = stringResource(R.string.correct_metadata),
                description = stringResource(R.string.correct_metadata_desc),
                selected = settings.batchCorrectMetadata,
            ) { settings.updateBatchCorrectMetadata(!settings.batchCorrectMetadata) }

            SwitchItem(
                label = stringResource(R.string.auto_try_providers),
                description = stringResource(R.string.auto_try_providers_desc),
                selected = settings.batchAutoTryProviders,
            ) { settings.updateBatchAutoTryProviders(!settings.batchAutoTryProviders) }

            SwitchItem(
                label = stringResource(R.string.add_unsynced_if_no_synced),
                description = stringResource(R.string.add_unsynced_if_no_synced_desc),
                selected = settings.batchAddUnsyncedFallback,
            ) { settings.updateBatchAddUnsyncedFallback(!settings.batchAddUnsyncedFallback) }

            Text(
                text = stringResource(R.string.private_lrc_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showLegacyPrompt) {
        LegacyPromptDialog(
            onConfirm = { startAndShowProgress() },
            onDismiss = { showLegacyPrompt = false },
        )
    }
}
