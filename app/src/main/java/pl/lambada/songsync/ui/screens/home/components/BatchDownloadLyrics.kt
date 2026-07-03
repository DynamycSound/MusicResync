package pl.lambada.songsync.ui.screens.home.components

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.ui.screens.home.components.batchDownload.BatchDownloadWarningDialog
import pl.lambada.songsync.ui.screens.home.components.batchDownload.DownloadCompleteDialog
import pl.lambada.songsync.ui.screens.home.components.batchDownload.DownloadProgressDialog
import pl.lambada.songsync.ui.screens.home.components.batchDownload.LegacyPromptDialog
import pl.lambada.songsync.ui.screens.home.components.batchDownload.RateLimitedDialog
import kotlin.math.roundToInt

@SuppressLint("StringFormatMatches")
@Composable
fun BatchDownloadLyrics(viewModel: HomeViewModel, onDone: () -> Unit) {
    val songs = viewModel.songsToBatchDownload
    var uiState by rememberSaveable { mutableStateOf(UiState.Warning) }
    var successCount by rememberSaveable { mutableIntStateOf(0) }
    var noLyricsCount by rememberSaveable { mutableIntStateOf(0) }
    var failedCount by rememberSaveable { mutableIntStateOf(0) }
    var skippedCount by rememberSaveable { mutableIntStateOf(0) }
    // Toggles live in persisted user settings (not per-dialog rememberSaveable) so choices like
    // "Embed lyrics to file" and "Add unsynced fallback" survive between runs and app restarts.
    val settings = viewModel.userSettingsController
    val correctMetadata = settings.batchCorrectMetadata
    val skipExisting = settings.batchSkipExisting
    val autoTryProviders = settings.batchAutoTryProviders
    val saveLrc = settings.batchSaveLrc                       // default ON (Samsung-compatible)
    val embedLyrics = settings.batchEmbedLyrics               // default OFF
    val addUnsyncedFallback = settings.batchAddUnsyncedFallback // default OFF (synced-only)
    val count = successCount + failedCount + noLyricsCount + skippedCount
    val total = songs.size
    // Songs the batch will actually process when skipExisting is on = those WITHOUT synced lyrics. UNSYNCED
    // (a plain .lrc with no timestamps) is processable too — it was previously omitted, undercounting the warning.
    val songsWithoutLyrics = songs.count {
        val st = viewModel.lyricStateFor(it)
        st == LyricState.NO_LYRICS || st == LyricState.FAILED || st == LyricState.UNSYNCED
    }
    val songsToProcess = if (skipExisting) songsWithoutLyrics else total
    val context = LocalContext.current
    val startBatchDownload = remember {
        {
            // Always restart the progress from zero. rememberSaveable keeps the counters across the dialog being
            // dismissed/reopened, which made a fresh run look like it was resuming from where it was cancelled.
            // The actual fetch (with skipExisting) already begins at the first song without synced lyrics.
            successCount = 0
            noLyricsCount = 0
            failedCount = 0
            skippedCount = 0
            // Read the toggles from settings at call time: this lambda is remember{}ed, so capturing the
            // composable-local snapshot values would freeze them at their first-composition state.
            viewModel.batchDownloadLyrics(
                context,
                correctMetadata = settings.batchCorrectMetadata,
                skipExisting = settings.batchSkipExisting,
                autoTryProviders = settings.batchAutoTryProviders,
                saveLrc = settings.batchSaveLrc,
                embedLyrics = settings.batchEmbedLyrics,
                addUnsyncedFallback = settings.batchAddUnsyncedFallback,
                onProgressUpdate = { newSuccessCount, newNoLyricsCount, newFailedCount, newSkippedCount ->
                    successCount = newSuccessCount
                    noLyricsCount = newNoLyricsCount
                    failedCount = newFailedCount
                    skippedCount = newSkippedCount
                },
                onDownloadComplete = { uiState = UiState.Done },
                onRateLimitReached = { uiState = UiState.RateLimited }
            )
        }
    }

    when (uiState) {
        UiState.Cancelled -> {
            // Actually stop the running batch (not just close the dialog) so it can't keep saving in the
            // background after the user pressed Stop.
            viewModel.cancelBatch()
            onDone()
        }
        UiState.Warning -> BatchDownloadWarningDialog(
            songsToProcess = songsToProcess,
            onConfirm = {
                uiState = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    UiState.LegacyPrompt
                } else {
                    startBatchDownload()
                    UiState.Pending
                }
            },
            onDismiss = { uiState = UiState.Cancelled },
            saveLrc = saveLrc,
            onSaveLrcChangeRequest = settings::updateBatchSaveLrc,
            embedLyrics = embedLyrics,
            onEmbedLyricsChangeRequest = settings::updateBatchEmbedLyrics,
            correctMetadata = correctMetadata,
            onCorrectMetadataChangeRequest = settings::updateBatchCorrectMetadata,
            skipExisting = skipExisting,
            onSkipExistingChangeRequest = settings::updateBatchSkipExisting,
            autoTryProviders = autoTryProviders,
            onAutoTryProvidersChangeRequest = settings::updateBatchAutoTryProviders,
            addUnsyncedFallback = addUnsyncedFallback,
            onAddUnsyncedFallbackChangeRequest = settings::updateBatchAddUnsyncedFallback,
        )

        UiState.LegacyPrompt -> LegacyPromptDialog(
            onConfirm = {
                uiState = UiState.Pending
                startBatchDownload()
            },
            onDismiss = { uiState = UiState.Cancelled }
        )

        UiState.Pending -> {
            val percentage =
                if (total != 0) (count.toFloat() / total.toFloat() * 100).roundToInt() else 0

            DownloadProgressDialog(
                // Guard against total == 0 (empty batch): `count % 0` throws ArithmeticException and crashes.
                currentSongTitle = songs.getOrNull(if (total > 0) count % total else 0)?.title,
                count = count,
                total = total,
                percentage = percentage,
                successCount = successCount,
                noLyricsCount = noLyricsCount,
                failedCount = failedCount,
                skippedCount = skippedCount,
                onCancel = { uiState = UiState.Cancelled },
                disableMarquee = viewModel.userSettingsController.disableMarquee
            )
        }

        UiState.Done -> DownloadCompleteDialog(
            successCount = successCount,
            noLyricsCount = noLyricsCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            onDismiss = { uiState = UiState.Cancelled }
        )

        UiState.RateLimited -> RateLimitedDialog(onDismiss = { uiState = UiState.Cancelled })
    }
}

enum class UiState {
    Warning, LegacyPrompt, Pending, Done, RateLimited, Cancelled
}