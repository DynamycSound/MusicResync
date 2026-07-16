package pl.lambada.songsync.ui.screens.batch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.app.NotificationManagerCompat
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.util.batch.BatchDownloadController
import pl.lambada.songsync.util.batch.BatchDownloadController.Status
import pl.lambada.songsync.util.batch.BatchDownloadService
import pl.lambada.songsync.util.ext.toLrcFile
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.showToast


/**
 * Full-screen live view of the batch download. Replaces the old cramped progress dialog.
 *
 * The heart of the screen is a pager whose last page is "live" (the song currently being matched); every
 * finished song becomes a new page just before it, so a match visually slides away to the left while the next
 * song arrives from the right. Swiping back browses previous songs (with their result and saved lyrics) and
 * never gets yanked back to live; a "Show live" chip returns on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchProgressScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = BatchDownloadController

    val processedCount = controller.processed.size
    // Pages: one per finished song + the live page at the end.
    val pagerState = rememberPagerState(initialPage = processedCount) { controller.processed.size + 1 }
    val onLivePage = pagerState.currentPage >= processedCount

    // Follow the live edge only when the user is already on it. Browsing history is never interrupted.
    var followLive by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) followLive = pagerState.currentPage >= controller.processed.size
    }
    LaunchedEffect(processedCount) {
        if (followLive && pagerState.currentPage < processedCount) {
            pagerState.animateScrollToPage(processedCount)
        }
    }

    var showKeepAliveDialog by remember { mutableStateOf(false) }
    var lyricsSheetSong by remember { mutableStateOf<Song?>(null) }
    var categorySheet by remember { mutableStateOf<ResultCategory?>(null) }

    // The user is looking at the results now; the summary notification has served its purpose.
    LaunchedEffect(controller.status) {
        if (controller.status != Status.RUNNING) {
            NotificationManagerCompat.from(context).cancel(BatchDownloadService.RESULTS_NOTIFICATION_ID)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.batch_download_lyrics)) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                beyondViewportPageCount = 1,
            ) { page ->
                if (page < controller.processed.size) {
                    val entry = controller.processed[page]
                    SongPage(
                        song = entry.song,
                        subtitle = resultLabel(entry.info.state),
                        provider = entry.info.provider?.displayName,
                        showViewLyrics = entry.info.hasLyrics || entry.info.state == LyricState.UNSYNCED,
                        searching = false,
                        onClick = { lyricsSheetSong = entry.song },
                    )
                } else {
                    LivePage(controller = controller)
                }
            }

            // History position + jump back to live. Only visible while browsing.
            AnimatedVisibility(visible = !onLivePage, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.processed_of_total,
                            (pagerState.currentPage + 1).coerceAtMost(processedCount), processedCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = {
                        followLive = true
                        scope.launch { pagerState.animateScrollToPage(controller.processed.size) }
                    }) {
                        Text(stringResource(R.string.show_live))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            CountersGrid(controller, onCategoryClick = { categorySheet = it })

            // Unsynced lyrics found for songs with no synced match. One tap adds all of them
            // (and keeps auto-adding new ones for the rest of the run).
            val pendingUnsynced = controller.pendingUnsynced.size
            AnimatedVisibility(visible = pendingUnsynced > 0) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.unsynced_available, pendingUnsynced),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.unsynced_available_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        TextButton(onClick = {
                            controller.addAllPendingUnsynced(context) { added ->
                                scope.launch(Dispatchers.Main) {
                                    showToast(context, R.string.unsynced_added_toast, added)
                                }
                            }
                        }) { Text(stringResource(R.string.add_all)) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Overall progress.
            val done = controller.processedCount
            val total = controller.total
            val fraction by animateFloatAsState(
                targetValue = if (total > 0) done.toFloat() / total else 0f,
                animationSpec = tween(400),
                label = "batchProgress",
            )
            Column(Modifier.padding(horizontal = 24.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.processed_of_total, done, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (controller.skippedCount > 0) {
                        Text(
                            text = stringResource(R.string.batch_skipped_label, controller.skippedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (controller.status == Status.RATE_LIMITED) {
                Text(
                    text = stringResource(R.string.batch_rate_limited_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Actions.
            if (controller.isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { showKeepAliveDialog = true }) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.run_in_background))
                    }
                    OutlinedButton(onClick = { controller.stop() }) {
                        Text(stringResource(R.string.stop))
                    }
                }
            } else {
                Button(onClick = onNavigateBack) { Text(stringResource(R.string.close)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showKeepAliveDialog) {
        KeepAliveDialog(onDismiss = { showKeepAliveDialog = false })
    }

    lyricsSheetSong?.let { song ->
        LyricsPreviewSheet(song = song, onDismiss = { lyricsSheetSong = null })
    }

    categorySheet?.let { category ->
        ResultCategorySheet(
            category = category,
            controller = controller,
            onDismiss = { categorySheet = null },
            onSongClick = { song -> lyricsSheetSong = song },
        )
    }
}

/** The tappable result buckets on the progress screen. Each opens a drawer listing the affected songs. */
enum class ResultCategory(val titleRes: Int) {
    SYNCED(R.string.batch_category_synced_title),
    UNSYNCED(R.string.batch_category_unsynced_title),
    NOT_FOUND(R.string.batch_category_not_found_title),
    FAILED(R.string.batch_category_failed_title);

    fun matches(state: LyricState): Boolean = when (this) {
        SYNCED -> state == LyricState.SYNCED || state == LyricState.REVIEW
        UNSYNCED -> state == LyricState.UNSYNCED
        NOT_FOUND -> state == LyricState.NO_LYRICS
        FAILED -> state == LyricState.FAILED
    }
}

/**
 * Drawer listing every song that landed in a result bucket. Opens at half the screen and can be dragged up
 * to full height (or down to dismiss). Tapping a song that has lyrics opens the lyrics preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultCategorySheet(
    category: ResultCategory,
    controller: BatchDownloadController,
    onDismiss: () -> Unit,
    onSongClick: (Song) -> Unit,
) {
    // skipPartiallyExpanded = false => the sheet rests at ~half screen first and expands to full when dragged.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val entries = controller.processed.filter { category.matches(it.info.state) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entries.size.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.batch_category_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        CategorySongRow(
                            entry = entry,
                            showLyricsHint = entry.info.hasLyrics || entry.info.state == LyricState.UNSYNCED,
                            onClick = { onSongClick(entry.song) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CategorySongRow(
    entry: BatchDownloadController.ProcessedSong,
    showLyricsHint: Boolean,
    onClick: () -> Unit,
) {
    val song = entry.song
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(LocalContext.current).data(song.imgUri).apply {
            placeholder(R.drawable.ic_song)
            error(R.drawable.ic_song)
        }.build()
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = showLyricsHint, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painter,
            contentDescription = stringResource(R.string.album_cover),
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitleParts = listOfNotNull(
                song.artist,
                entry.info.provider?.displayName,
            )
            Text(
                text = subtitleParts.joinToString(" · ").ifBlank { stringResource(R.string.unknown) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showLyricsHint) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.tap_to_view_lyrics),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The live (rightmost) page: the song currently being matched, or the run summary once finished. */
@Composable
private fun LivePage(controller: BatchDownloadController) {
    when (controller.status) {
        Status.RUNNING -> {
            val song = controller.currentSong
            if (song != null) {
                SongPage(
                    song = song,
                    subtitle = stringResource(R.string.batch_searching),
                    provider = null,
                    showViewLyrics = false,
                    searching = true,
                    onClick = null,
                )
            } else {
                StatusPage(icon = { CircularProgressIndicator(Modifier.size(48.dp)) }, title = stringResource(R.string.please_wait))
            }
        }
        Status.DONE -> StatusPage(
            icon = {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)
                )
            },
            title = stringResource(R.string.batch_done_title),
            subtitle = elapsedLabel(controller),
        )
        Status.RATE_LIMITED, Status.CANCELLED -> StatusPage(
            icon = {
                Icon(
                    Icons.Filled.HourglassEmpty, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp)
                )
            },
            title = stringResource(R.string.batch_stopped_title),
        )
        Status.IDLE -> StatusPage(icon = {}, title = "")
    }
}

@Composable
private fun StatusPage(icon: @Composable () -> Unit, title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Finished in 4 min 12 sec" for the Done page; null when the run bounds are unknown. */
@Composable
private fun elapsedLabel(controller: BatchDownloadController): String? {
    val start = controller.runStartedAt
    val end = controller.runFinishedAt
    if (start <= 0L || end <= start) return null
    val totalSec = ((end - start) / 1000).coerceAtLeast(1)
    val min = (totalSec / 60).toInt()
    val sec = (totalSec % 60).toInt()
    val duration = if (min > 0) stringResource(R.string.duration_min_sec, min, sec)
    else stringResource(R.string.duration_sec, sec)
    return stringResource(R.string.batch_finished_in, duration)
}

/** One pager page: big album art, title/artist, and a small state line underneath. */
@Composable
private fun SongPage(
    song: Song,
    subtitle: String,
    provider: String?,
    showViewLyrics: Boolean,
    searching: Boolean,
    onClick: (() -> Unit)?,
) {
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(LocalContext.current).data(song.imgUri).apply {
            placeholder(R.drawable.ic_song)
            error(R.drawable.ic_song)
        }.build()
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 32.dp)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Image(
                painter = painter,
                contentDescription = stringResource(R.string.album_cover),
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd)
                        .padding(2.dp),
                    strokeWidth = 3.dp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = song.title ?: stringResource(R.string.unknown),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artist ?: stringResource(R.string.unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = provider?.let { "$subtitle · $it" } ?: subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showViewLyrics) {
            Text(
                text = stringResource(R.string.tap_to_view_lyrics),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(0.9f).padding(top = 2.dp),
            )
        }
    }
}

/** 2x2 grid of animated counters. Neutral M3 tones only, the label carries the meaning. Each tile opens a
 *  drawer listing the songs in that bucket. */
@Composable
private fun CountersGrid(controller: BatchDownloadController, onCategoryClick: (ResultCategory) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CounterTile(
                label = stringResource(R.string.batch_synced_label),
                value = controller.syncedCount,
                emphasized = true,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryClick(ResultCategory.SYNCED) },
            )
            CounterTile(
                label = stringResource(R.string.batch_unsynced_label),
                value = controller.unsyncedCount,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryClick(ResultCategory.UNSYNCED) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CounterTile(
                label = stringResource(R.string.batch_not_found_label),
                value = controller.noLyricsCount,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryClick(ResultCategory.NOT_FOUND) },
            )
            CounterTile(
                label = stringResource(R.string.batch_failed_label),
                value = controller.failedCount,
                modifier = Modifier.weight(1f),
                onClick = { onCategoryClick(ResultCategory.FAILED) },
            )
        }
    }
}

@Composable
private fun CounterTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // The number slides up and the old one slides out as it increments.
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)))
                        .togetherWith(slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(160)))
                },
                label = "counter",
            ) { v ->
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LyricState.label: Int
    get() = when (this) {
        LyricState.SYNCED, LyricState.REVIEW -> R.string.previous_result_synced
        LyricState.UNSYNCED -> R.string.previous_result_unsynced
        LyricState.HAS_LYRICS -> R.string.previous_result_skipped
        LyricState.FAILED -> R.string.previous_result_failed
        else -> R.string.previous_result_none
    }

@Composable
private fun resultLabel(state: LyricState): String = stringResource(state.label)

/** Bottom sheet showing the saved .lrc content for a song browsed in the history pager. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsPreviewSheet(song: Song, onDismiss: () -> Unit) {
    val lyrics by produceState<String?>(initialValue = null, song.filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { song.filePath?.toLrcFile()?.takeIf { it.exists() }?.readText() }.getOrNull()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = song.title ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = song.artist ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            when (val text = lyrics) {
                null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Text(
                    text = text.ifBlank { stringResource(R.string.no_lyrics_only) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The "don't kill my app" guidance shown when the user chooses to run in background. The batch is ALREADY
 * running in an app-scoped foreground service at this point, so nothing here can interrupt it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun KeepAliveDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val notifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) else null
    val notificationsGranted = notifPermission?.status?.isGranted ?: true

    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    // Re-check when the dialog regains focus after the system prompt.
    LaunchedEffect(Unit) { batteryUnrestricted = isIgnoringBatteryOptimizations(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keep_alive_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.keep_alive_intro))

                if (!notificationsGranted) {
                    OutlinedButton(
                        onClick = { notifPermission?.launchPermissionRequest() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.allow_notifications)) }
                } else {
                    StepDone(stringResource(R.string.keep_alive_step_notifications))
                }

                if (!batteryUnrestricted) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                            batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.disable_battery_optimization)) }
                } else {
                    StepDone(stringResource(R.string.battery_already_unrestricted))
                }

                Text(
                    text = stringResource(R.string.keep_alive_step_recents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
        },
    )
}

@Composable
private fun StepDone(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
