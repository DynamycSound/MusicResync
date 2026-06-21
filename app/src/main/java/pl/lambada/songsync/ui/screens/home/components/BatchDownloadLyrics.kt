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
    var correctMetadata by rememberSaveable { mutableStateOf(false) }
    var skipExisting by rememberSaveable { mutableStateOf(true) }
    var autoTryProviders by rememberSaveable { mutableStateOf(true) }
    var saveLrc by rememberSaveable { mutableStateOf(true) }            // default ON (Samsung-compatible)
    var embedLyrics by rememberSaveable { mutableStateOf(false) }       // default OFF
    val count = successCount + failedCount + noLyricsCount
    val total = songs.size
    val songsWithoutLyrics = songs.count {
        val st = viewModel.lyricStateFor(it)
        st == LyricState.NO_LYRICS || st == LyricState.FAILED
    }
    val songsToProcess = if (skipExisting) songsWithoutLyrics else total
    val context = LocalContext.current
    val startBatchDownload = remember {
        {
            viewModel.batchDownloadLyrics(
                context,
                correctMetadata = correctMetadata,
                skipExisting = skipExisting,
                autoTryProviders = autoTryProviders,
                saveLrc = saveLrc,
                embedLyrics = embedLyrics,
                onProgressUpdate = { newSuccessCount, newNoLyricsCount, newFailedCount ->
                    successCount = newSuccessCount
                    noLyricsCount = newNoLyricsCount
                    failedCount = newFailedCount
                },
                onDownloadComplete = { uiState = UiState.Done },
                onRateLimitReached = { uiState = UiState.RateLimited }
            )
        }
    }

    when (uiState) {
        UiState.Cancelled -> onDone()
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
            onSaveLrcChangeRequest = { saveLrc = it },
            embedLyrics = embedLyrics,
            onEmbedLyricsChangeRequest = { embedLyrics = it },
            correctMetadata = correctMetadata,
            onCorrectMetadataChangeRequest = { correctMetadata = it },
            skipExisting = skipExisting,
            onSkipExistingChangeRequest = { skipExisting = it },
            autoTryProviders = autoTryProviders,
            onAutoTryProvidersChangeRequest = { autoTryProviders = it },
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
                currentSongTitle = songs.getOrNull(count % total)?.title,
                count = count,
                total = total,
                percentage = percentage,
                successCount = successCount,
                noLyricsCount = noLyricsCount,
                failedCount = failedCount,
                onCancel = { uiState = UiState.Cancelled },
                disableMarquee = viewModel.userSettingsController.disableMarquee
            )
        }

        UiState.Done -> DownloadCompleteDialog(
            successCount = successCount,
            noLyricsCount = noLyricsCount,
            failedCount = failedCount,
            onDismiss = { uiState = UiState.Cancelled }
        )

        UiState.RateLimited -> RateLimitedDialog(onDismiss = { uiState = UiState.Cancelled })
    }
}

enum class UiState {
    Warning, LegacyPrompt, Pending, Done, RateLimited, Cancelled
}