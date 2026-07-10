package pl.lambada.songsync.ui.screens.home.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.ui.screens.home.components.batchDownload.BatchDownloadWarningDialog
import pl.lambada.songsync.ui.screens.home.components.batchDownload.LegacyPromptDialog
import pl.lambada.songsync.util.matching.LyricState

/**
 * Entry point of a batch run: the options dialog (plus the legacy Android prompt). Confirming starts the
 * batch in the app-scoped [pl.lambada.songsync.util.batch.BatchDownloadController] and opens the full-screen
 * progress view, which replaced the old cramped progress dialog.
 */
@Composable
fun BatchDownloadLyrics(
    viewModel: HomeViewModel,
    onDone: () -> Unit,
    onNavigateToProgress: () -> Unit,
) {
    val songs = viewModel.songsToBatchDownload
    var showLegacyPrompt by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Toggles live in persisted user settings so choices like "Embed lyrics" and "Add unsynced fallback"
    // survive between runs and app restarts.
    val settings = viewModel.userSettingsController

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
        onDone()
        onNavigateToProgress()
    }

    if (showLegacyPrompt) {
        LegacyPromptDialog(
            onConfirm = { startAndShowProgress() },
            onDismiss = { onDone() }
        )
    } else {
        BatchDownloadWarningDialog(
            songsToProcess = songsToProcess,
            onConfirm = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) showLegacyPrompt = true
                else startAndShowProgress()
            },
            onDismiss = { onDone() },
            saveLrc = settings.batchSaveLrc,
            onSaveLrcChangeRequest = settings::updateBatchSaveLrc,
            embedLyrics = settings.batchEmbedLyrics,
            onEmbedLyricsChangeRequest = settings::updateBatchEmbedLyrics,
            correctMetadata = settings.batchCorrectMetadata,
            onCorrectMetadataChangeRequest = settings::updateBatchCorrectMetadata,
            skipExisting = settings.batchSkipExisting,
            onSkipExistingChangeRequest = settings::updateBatchSkipExisting,
            skipPreviouslyFailed = settings.batchSkipPreviouslyFailed,
            onSkipPreviouslyFailedChangeRequest = settings::updateBatchSkipPreviouslyFailed,
            autoTryProviders = settings.batchAutoTryProviders,
            onAutoTryProvidersChangeRequest = settings::updateBatchAutoTryProviders,
            addUnsyncedFallback = settings.batchAddUnsyncedFallback,
            onAddUnsyncedFallbackChangeRequest = settings::updateBatchAddUnsyncedFallback,
        )
    }
}
