package pl.lambada.songsync.nowplaying

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.ui.components.ArtworkBackdrop
import pl.lambada.songsync.ui.components.PanelButton
import pl.lambada.songsync.ui.components.SourceRow
import pl.lambada.songsync.ui.components.artworkPalette
import pl.lambada.songsync.ui.components.artworkPlayButtonColors
import pl.lambada.songsync.ui.components.PanelSide
import pl.lambada.songsync.ui.components.PopupPanel
import pl.lambada.songsync.ui.components.TimingPanel
import pl.lambada.songsync.ui.components.TimingResetButton
import pl.lambada.songsync.ui.components.WaveScrubber
import pl.lambada.songsync.ui.components.formatOffsetSeconds
import pl.lambada.songsync.util.LyricLine
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.currentLineIndex
import pl.lambada.songsync.util.matching.TextMatch

/** Lines light up this much before their timestamp, so the highlight never feels late. */
private const val LINE_LEAD_MS = 200L

/** After the user scrolls the lyrics by hand, auto-follow waits this long before taking over again. */
private const val MANUAL_SCROLL_HOLD_MS = 4_000L

/** How far the timing panel can move live lyrics either way. */
private const val LIVE_OFFSET_RANGE_MS = 5_000f

/**
 * Timing fixes the user made for streamed songs, remembered per song (a video's intro or a different cut is
 * the same every time it plays). Keyed by the cleaned title and artist, so the same song from another app matches.
 */
private fun offsetPrefs(context: Context) = context.getSharedPreferences("live_lyrics_offsets", Context.MODE_PRIVATE)

private fun offsetKey(track: NowPlayingTrack): String {
    val query = NowPlayingLogic.searchQuery(track.title, track.artist)
    return TextMatch.normalizeForCompare(query.title) + "|" + TextMatch.normalizeForCompare(query.artist)
}

/**
 * Live lyrics for the song another app is playing (Spotify, YouTube, Samsung Music…), opened from the Home
 * "Now playing" card. Follows that player: the current line is highlighted and kept in view, tapping a line seeks
 * there, and the controls drive the player itself. Read-only: nothing is saved, so streamed songs never get an
 * "add lyrics to file" step. Library songs show their own saved lyrics when they have them.
 *
 * @param librarySongs the loaded library, to recognise a song a local player is playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLyricsScreen(
    viewModel: LiveLyricsViewModel,
    librarySongs: List<Song>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // The last track that was playing. A pause makes the observer report "nothing playing", but the lyrics
    // should stay put (and the play button should resume it), so null never replaces a known track here.
    var track by remember { mutableStateOf<NowPlayingTrack?>(null) }
    LifecycleStartEffect(Unit) {
        val observer = NowPlayingObserver(context) { playing -> if (playing != null) track = playing }
        observer.start()
        onStopOrDispose { observer.stop() }
    }

    // Lyrics are meant to be read along: keep the screen on while this view is open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val current = track
    LaunchedEffect(current?.packageName, current?.title, current?.artist, librarySongs.size) {
        val playing = current ?: return@LaunchedEffect
        val local = withContext(Dispatchers.Default) {
            val query = NowPlayingLogic.searchQuery(playing.title, playing.artist)
            NowPlayingLogic.findLocalMatch(playing.title, playing.artist, playing.durationMs, librarySongs)
                ?: NowPlayingLogic.findLocalMatch(query.title, query.artist, playing.durationMs, librarySongs)
        }
        viewModel.load(playing, local?.filePath)
    }

    val controller = current?.controller
    var positionMs by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(controller) {
        while (true) {
            val state = controller?.playbackState
            isPlaying = state?.state == PlaybackState.STATE_PLAYING
            positionMs = state?.let {
                NowPlayingLogic.livePositionMs(
                    playing = isPlaying,
                    positionMs = it.position,
                    lastUpdateMs = it.lastPositionUpdateTime,
                    speed = it.playbackSpeed,
                    nowMs = SystemClock.elapsedRealtime(),
                )
            }
            delay(if (isPlaying) 80 else 300)
        }
    }

    // Positive: lines show sooner. Loaded per song, saved when the user lets go of the control.
    val offsetKey = remember(current?.title, current?.artist) { current?.let(::offsetKey) }
    var offsetMs by remember(offsetKey) {
        mutableFloatStateOf(offsetKey?.let { offsetPrefs(context).getFloat(it, 0f) } ?: 0f)
    }
    fun saveOffset() {
        val key = offsetKey ?: return
        offsetPrefs(context).edit().apply {
            if (offsetMs == 0f) remove(key) else putFloat(key, offsetMs)
        }.apply()
    }
    // One side panel open at a time.
    var openPanel by remember { mutableStateOf<PanelSide?>(null) }
    BackHandler(enabled = openPanel != null) { openPanel = null }

    // The page, scrubber and play button take their colours from the artwork (see ArtworkBackdrop).
    val palette = remember(current?.artwork) { current?.artwork?.let(::artworkPalette) }
    ArtworkBackdrop(palette) { colors ->
        val vivid = colors.vivid
        val normal = colors.normal
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = current?.appLabel?.let { stringResource(R.string.now_playing_in_app, it) }
                                    ?: stringResource(R.string.now_playing),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                if (current != null) {
                    val lyrics = viewModel.lyrics
                    PlayerControls(
                        controller = controller,
                        positionMs = positionMs,
                        durationMs = current.durationMs,
                        isPlaying = isPlaying,
                        accent = vivid,
                        secondary = normal,
                        start = {
                            // Timing only means something for synced lyrics.
                            PanelButton(
                                icon = Icons.Default.Timer,
                                contentDescription = stringResource(R.string.timing_title),
                                open = openPanel == PanelSide.Start,
                                onClick = { openPanel = if (openPanel == PanelSide.Start) null else PanelSide.Start },
                                badge = offsetMs.takeIf { it != 0f }?.let(::formatOffsetSeconds),
                                enabled = lyrics is LiveLyrics.Synced,
                            )
                        },
                        end = {
                            PanelButton(
                                icon = Icons.Default.Lyrics,
                                contentDescription = stringResource(R.string.source_title),
                                open = openPanel == PanelSide.End,
                                onClick = { openPanel = if (openPanel == PanelSide.End) null else PanelSide.End },
                                highlighted = viewModel.pinnedProvider != null,
                            )
                        },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (current == null) {
                    Message(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.live_lyrics_nothing_playing),
                        body = stringResource(R.string.live_lyrics_nothing_playing_body),
                    )
                    return@Column
                }
                TrackHeader(current)
                val lyrics = viewModel.lyrics
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = lyrics,
                        contentKey = { it::class },
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "liveLyrics",
                        modifier = Modifier
                            .fillMaxSize()
                            .fadingEdges(top = 28.dp, bottom = 56.dp),
                    ) { state ->
                        when (state) {
                            LiveLyrics.Searching -> Searching()
                            is LiveLyrics.Synced -> SyncedLyrics(
                                lines = state.lines,
                                source = state.source,
                                positionMs = { positionMs },
                                offsetMs = { offsetMs.toLong() },
                                onSeek = { ms -> seekTo(controller, ms) },
                            )
                            is LiveLyrics.Plain -> PlainLyrics(state.text, state.source)
                            is LiveLyrics.NotFound -> if (state.provider != null) Message(
                                icon = Icons.Default.MusicOff,
                                title = stringResource(R.string.live_lyrics_not_found_on, state.provider.displayName),
                                body = stringResource(R.string.live_lyrics_not_found_on_body),
                                action = stringResource(R.string.source_auto) to { viewModel.pickProvider(null) },
                            ) else Message(
                                icon = Icons.Default.MusicOff,
                                title = stringResource(R.string.live_lyrics_not_found),
                                body = stringResource(R.string.live_lyrics_not_found_body),
                                action = stringResource(R.string.try_again) to { viewModel.retry() },
                            )
                            LiveLyrics.NoConnection -> Message(
                                icon = Icons.Default.WifiOff,
                                title = stringResource(R.string.live_lyrics_no_connection),
                                body = stringResource(R.string.live_lyrics_no_connection_body),
                                action = stringResource(R.string.try_again) to { viewModel.retry() },
                            )
                        }
                    }

                    PopupPanel(
                        side = PanelSide.Start,
                        visible = openPanel == PanelSide.Start && lyrics is LiveLyrics.Synced,
                        onDismiss = { openPanel = null },
                        icon = Icons.Default.Timer,
                        title = stringResource(R.string.timing_title),
                        headerAction = { TimingResetButton(offsetMs) { offsetMs = 0f; saveOffset() } },
                    ) {
                        TimingPanel(
                            offsetMs = offsetMs,
                            rangeMs = LIVE_OFFSET_RANGE_MS,
                            onOffsetChange = { offsetMs = it },
                            onCommit = ::saveOffset,
                            accent = vivid,
                        )
                    }
                    PopupPanel(
                        side = PanelSide.End,
                        visible = openPanel == PanelSide.End,
                        onDismiss = { openPanel = null },
                        icon = Icons.Default.Lyrics,
                        title = stringResource(R.string.source_title),
                    ) {
                        SourcePanel(
                            providers = viewModel.availableProviders,
                            pinned = viewModel.pinnedProvider,
                            shownFrom = (lyrics as? LiveLyrics.Synced)?.source ?: (lyrics as? LiveLyrics.Plain)?.source,
                            onPick = { provider ->
                                viewModel.pickProvider(provider)
                                openPanel = null
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Lyrics fade out towards the header and the controls instead of being cut off by a hard edge. */
private fun Modifier.fadingEdges(top: Dp, bottom: Dp) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height.coerceAtLeast(1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                (top.toPx() / h).coerceIn(0f, 0.5f) to Color.Black,
                (1f - bottom.toPx() / h).coerceIn(0.5f, 1f) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private fun canSeek(controller: MediaController?): Boolean =
    (controller?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L

private fun seekTo(controller: MediaController?, ms: Long) {
    if (canSeek(controller)) controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
}

@Composable
private fun TrackHeader(track: NowPlayingTrack) {
    val query = remember(track.title, track.artist) { NowPlayingLogic.searchQuery(track.title, track.artist) }
    Row(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(14.dp)
        val image = remember(track.artwork) { track.artwork?.asImageBitmap() }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.album_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(shape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = query.title.ifBlank { track.title },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = query.artist.ifBlank { track.artist ?: stringResource(R.string.unknown) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The right-hand panel: "Best match" (the automatic search) or one source only. [shownFrom] is where the lyrics on
 * screen came from, so the automatic row can say which source won.
 */
@Composable
private fun SourcePanel(
    providers: List<Providers>,
    pinned: Providers?,
    shownFrom: Providers?,
    onPick: (Providers?) -> Unit,
) {
    SourceRow(
        label = stringResource(R.string.source_auto),
        detail = if (pinned == null) shownFrom?.displayName else null,
        selected = pinned == null,
        onClick = { onPick(null) },
    )
    providers.forEach { provider ->
        SourceRow(
            label = provider.displayName,
            detail = null,
            selected = pinned == provider,
            onClick = { onPick(provider) },
        )
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    source: Providers?,
    positionMs: () -> Long?,
    offsetMs: () -> Long,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    // Only a change of line recomposes the list, not every position tick.
    val currentIndex by remember(lines) {
        derivedStateOf { positionMs()?.let { currentLineIndex(lines, it + LINE_LEAD_MS + offsetMs()) } ?: -1 }
    }
    val positionKnown by remember { derivedStateOf { positionMs() != null } }

    var lastManualScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) lastManualScroll = SystemClock.uptimeMillis()
        }
    }

    // Same karaoke follow as the library player: glide the active line to just above the middle.
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - lastManualScroll < MANUAL_SCROLL_HOLD_MS) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        if (item != null) {
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val anchor = info.viewportStartOffset + viewport * 0.35f
            listState.animateScrollBy(item.offset + item.size / 2f - anchor, tween(600, easing = FastOutSlowInEasing))
        } else {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 280.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val active = i == currentIndex
            val color by animateColorAsState(
                when {
                    active || !positionKnown -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
                },
                label = "lineColor",
            )
            val scale by animateFloatAsState(if (active) 1.05f else 1f, label = "lineScale")
            Text(
                text = line.text.ifBlank { "♪" },
                color = color,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSeek(line.timeMs - offsetMs()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        item { SourceFooter(source) }
    }
}

@Composable
private fun PlainLyrics(text: String, source: Providers?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.live_lyrics_unsynced),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Text(
            text = text,
            fontSize = 21.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        SourceFooter(source, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SourceFooter(source: Providers?, modifier: Modifier = Modifier) {
    Text(
        text = source?.let { stringResource(R.string.live_lyrics_source, it.displayName) }
            ?: stringResource(R.string.live_lyrics_source_file),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 24.dp),
    )
}

@Composable
private fun Searching() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.live_lyrics_finding),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * Progress (draggable when the player supports seeking) and previous / play-pause / next for the playing app, with
 * [start] and [end] (the timing and source buttons) at the ends of the button row.
 */
@Composable
private fun PlayerControls(
    controller: MediaController?,
    positionMs: Long?,
    durationMs: Long?,
    isPlaying: Boolean,
    accent: Color,
    secondary: Color,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 12.dp)
    ) {
        if (durationMs != null && positionMs != null) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            WaveScrubber(
                value = dragging ?: positionMs.coerceIn(0, durationMs).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { seekTo(controller, it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                playing = isPlaying,
                enabled = canSeek(controller),
                activeColor = accent,
                secondaryColor = secondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(dragging?.toLong() ?: positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { start() }
            IconButton(onClick = { controller?.transportControls?.skipToPrevious() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.live_lyrics_previous),
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(
                onClick = {
                    val controls = controller?.transportControls ?: return@FilledIconButton
                    if (isPlaying) controls.pause() else controls.play()
                },
                modifier = Modifier.size(68.dp),
                colors = artworkPlayButtonColors(accent),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.live_lyrics_pause else R.string.live_lyrics_play),
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { controller?.transportControls?.skipToNext() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.live_lyrics_next),
                    modifier = Modifier.size(32.dp),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { end() }
        }
    }
}
