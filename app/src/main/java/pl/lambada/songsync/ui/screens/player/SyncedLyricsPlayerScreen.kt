package pl.lambada.songsync.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Job
import java.net.UnknownHostException
import pl.lambada.songsync.nowplaying.FoundLyrics
import pl.lambada.songsync.ui.components.ArtworkBackdrop
import pl.lambada.songsync.ui.components.SourceRow
import pl.lambada.songsync.ui.components.artworkPalette
import pl.lambada.songsync.ui.components.artworkPlayButtonColors
import pl.lambada.songsync.ui.components.decodeCoverThumbnail
import pl.lambada.songsync.util.Providers
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.PanelButton
import pl.lambada.songsync.ui.components.PanelSide
import pl.lambada.songsync.ui.components.PopupPanel
import pl.lambada.songsync.ui.components.TimingPanel
import pl.lambada.songsync.ui.components.TimingResetButton
import pl.lambada.songsync.ui.components.WaveScrubber
import pl.lambada.songsync.ui.components.formatOffsetSeconds
import pl.lambada.songsync.ui.screens.lyricsFetch.components.ThumbnailPickerDialog
import pl.lambada.songsync.util.EmbeddedLyrics
import pl.lambada.songsync.util.applyOffsetToLyrics
import pl.lambada.songsync.util.buildNeutralLrc
import pl.lambada.songsync.util.embedCoverInFile
import pl.lambada.songsync.util.embedLyricsInFile
import pl.lambada.songsync.util.getFileDescriptorFromPath
import pl.lambada.songsync.util.parseOffsetTagMs
import pl.lambada.songsync.util.parseSyncedLrc
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.cache.SongCacheEntry
import pl.lambada.songsync.util.ext.BackPressHandler
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.showToast
import pl.lambada.songsync.util.toCrlf
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Half-width of the stable, absolute offset slider range (±10s). A saved -3.9s sits visibly left of centre. */
private const val OFFSET_RANGE_MS = 10000f

/** Where the player's lyrics come from: what it opened with, the best match online, or one source. */
private sealed interface PlayerSource {
    data object Original : PlayerSource
    data object Best : PlayerSource
    data class One(val provider: Providers) : PlayerSource
}

private fun fmt(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * Samsung-Music-style synced lyrics player. Plays the local audio and highlights the current lyric line as the
 * song progresses, auto-scrolling to keep it centred. An offset slider nudges the timing; whenever it changes
 * the track seeks ~2.5s earlier so the user immediately hears + sees whether the new offset lines up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun SyncedLyricsPlayerScreen(
    filePath: String,
    songName: String,
    artists: String,
    onBack: () -> Unit,
    onFindOnline: () -> Unit = {},
    /** When arriving from "Adjust timing", the (not-yet-saved) lyrics are passed here so nothing is written
     *  until the user presses Apply or leaves via Back. Null when opening an already-saved song from Home. */
    initialLyrics: String? = null,
    /** Optional cover fetcher used by the overflow-menu thumbnail action. */
    onRequestCovers: (suspend () -> List<String>)? = null,
    /** The sources offered in the lyrics source panel, in the user's order. */
    sourceProviders: List<Providers> = emptyList(),
    /** Looks the song up online: on one source, or the best match across all of them when the source is null. */
    onFindLyrics: (suspend (provider: Providers?, durationMs: Long) -> FoundLyrics?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoSaveOnExit = initialLyrics != null
    var menuExpanded by remember { mutableStateOf(false) }
    var showThumbnailPicker by remember { mutableStateOf(false) }
    var hasCover by remember(filePath) { mutableStateOf<Boolean?>(null) }
    // The embedded cover, small: shown in the top bar and read for the page's colours. Bumped after a new thumbnail.
    var cover by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    var coverVersion by remember(filePath) { mutableIntStateOf(0) }
    // Null while still loading from disk. The sidecar .lrc is tried first, then lyrics EMBEDDED in the audio
    // tags (issue #11: embedded-only songs opened this player and it said "no lyrics" because only the sidecar
    // was ever read). The TagLib read is real I/O, so it happens off the main thread with a loading state.
    var lrcText by remember(filePath) { mutableStateOf(initialLyrics?.takeIf { it.isNotBlank() }) }
    LaunchedEffect(filePath) {
        if (lrcText == null) {
            lrcText = withContext(Dispatchers.IO) {
                runCatching {
                    File(filePath.substringBeforeLast('.') + ".lrc").takeIf { it.exists() }?.readText()
                }.getOrNull()
                    ?: runCatching { EmbeddedLyrics.read(filePath) }.getOrNull()
                    ?: ""
            }
        }
    }
    val lyricsLoaded = lrcText != null
    // The lyrics the player opened with (the saved file, or the ones handed over from "Adjust timing"), so the
    // source panel can always go back to them.
    var originalLrc by remember(filePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(lrcText) { if (originalLrc == null && lrcText != null) originalLrc = lrcText }
    // Set while the lyrics on screen came from the source panel instead: which source they're from.
    var onlineFrom by remember(filePath) { mutableStateOf<Providers?>(null) }
    // Reconcile the two timing models into one neutral form (zero applied offset, no [offset:] tag) so all the
    // preview/seek/save math can treat the user's offset as a single absolute value. A file in tag mode carries
    // [offset:N]; a direct-shifted file has the offset baked into its timestamps and we undo it here.
    val fileOffsetMs = remember(lrcText) { parseOffsetTagMs(lrcText.orEmpty()) }
    val cachedOffsetMs = remember(filePath) { SongCache.get(filePath)?.offsetMs ?: 0 }
    val fileUsesOffsetTag = fileOffsetMs != 0
    // Lyrics fresh from a source have no remembered offset baked in.
    val baseAppliedOffsetMs = when {
        fileUsesOffsetTag -> fileOffsetMs
        onlineFrom != null -> 0
        else -> cachedOffsetMs
    }
    val neutralLrc = remember(lrcText, baseAppliedOffsetMs, fileUsesOffsetTag) {
        buildNeutralLrc(lrcText.orEmpty(), baseAppliedOffsetMs, fileUsesOffsetTag)
    }
    val lines = remember(neutralLrc) { parseSyncedLrc(neutralLrc) }

    val player = remember {
        MediaPlayer().apply {
            runCatching { setDataSource(filePath); prepare() }
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    val durationMs = remember { runCatching { player.duration }.getOrDefault(0) }
    // Absolute offset (ms) applied on top of the neutral lyrics. Opens at whatever was already applied to the
    // file (its tag value, or the remembered cache value), coerced into the slider range. Because the lyrics are
    // neutral, this is the single source of truth for preview, seek and save — no delta bookkeeping.
    var offsetMs by remember(filePath) {
        mutableFloatStateOf(baseAppliedOffsetMs.toFloat().coerceIn(-OFFSET_RANGE_MS, OFFSET_RANGE_MS))
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(filePath, coverVersion) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val fd = getFileDescriptorFromPath(context, filePath, "r") ?: return@runCatching null
                TagLib.getFrontCover(fd.dup().detachFd())?.data
            }.getOrNull()
        }
        hasCover = bytes != null
        cover = bytes?.let { withContext(Dispatchers.Default) { runCatching { decodeCoverThumbnail(it) }.getOrNull() } }
    }
    val palette = remember(cover) { cover?.let(::artworkPalette) }

    // Pause the music (and, by extension, the lyric auto-scroll, which only advances while isPlaying) as soon as
    // the app leaves the foreground — otherwise playback kept running in the background after the user left.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching { if (player.isPlaying) player.pause() }
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-play on open so the user can immediately verify the sync (especially when arriving from the
    // result screen's "Adjust timing").
    LaunchedEffect(Unit) {
        runCatching { if (!player.isPlaying) { player.start(); isPlaying = true } }
    }

    // The offset-tuned LRC the user is currently previewing: the neutral lyrics with the slider's absolute offset
    // baked into the timestamps and no stray [offset:] tag. Shared by Apply (writes the sidecar .lrc) and
    // "Embed lyrics to file" (writes the audio tag) so both persist exactly what's on screen. Returns blank only
    // when there are genuinely no lyrics to save.
    fun currentTunedLrc(): String {
        val total = offsetMs.roundToInt()
        val file = File(filePath.substringBeforeLast('.') + ".lrc")
        val neutralBase = neutralLrc.ifBlank {
            runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { buildNeutralLrc(it, parseOffsetTagMs(it).let { tag -> if (tag != 0) tag else cachedOffsetMs }, parseOffsetTagMs(it) != 0) }
                .orEmpty()
        }
        if (neutralBase.isBlank()) return ""
        return if (total != 0) applyOffsetToLyrics(neutralBase, total) else neutralBase
    }

    // Persists the lyrics with the chosen timing, remembers the total offset for next time, marks the song as
    // having lyrics, and returns to Home (where the row now shows a green note). Player Apply ALWAYS bakes the
    // offset directly into the timestamps and strips any [offset:] tag. This is deliberate: external players are
    // inconsistent about honouring offset tags, while directly shifted timestamps work everywhere and match what
    // the user expects from an explicit timing adjustment.
    fun saveSync() {
        val total = offsetMs.roundToInt()
        val output = currentTunedLrc()
        if (output.isBlank()) { onBack(); return }
        val file = File(filePath.substringBeforeLast('.') + ".lrc")
        runCatching {
            // Bake the shift into the timestamps; ensure no stray offset tag remains. Player Apply intentionally
            // ignores the global "offset tag vs direct shift" preference and always writes the real shifted timing.
            file.writeText(output.toCrlf())
            lrcText = output
            // Remember the offset + that this song now has (synced) lyrics, keeping any provider memory.
            SongCache.update(filePath) { prev ->
                (prev ?: SongCacheEntry(LyricState.HAS_LYRICS)).copy(state = LyricState.HAS_LYRICS, offsetMs = total)
            }
        }.onSuccess {
            showToast(context, context.getString(R.string.sync_saved))
            onBack()
        }
    }

    // Writes the offset-tuned lyrics straight into the audio file's own tags (the LYRICS frame) instead of only
    // the sidecar .lrc. This restores original SongSync's detail-screen behaviour: keep nudging the offset and
    // embed the freshly tuned lyrics as many times as you like. Runs off the main thread (TagLib does file I/O)
    // and deliberately leaves the .lrc and the remembered offset untouched, so re-opening the player still
    // reconciles from a single source and never double-applies the shift.
    fun embedTunedLyrics() {
        val output = currentTunedLrc()
        if (output.isBlank()) {
            showToast(context, context.getString(R.string.error))
            return
        }
        scope.launch(Dispatchers.IO) {
            // embedLyricsInFile returns false (without throwing) when the tag write fails or is denied, so use its
            // boolean rather than assuming success.
            val embedded = runCatching { embedLyricsInFile(context, filePath, output) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                showToast(
                    context,
                    context.getString(if (embedded) R.string.embedded_lyrics_in_file else R.string.error)
                )
            }
        }
    }

    fun exitPlayer() {
        // When the player was opened from "Adjust timing & preview", the lyrics exist only in-memory until the
        // user confirms them. Back should behave like Apply for this preview flow so returning to Search/Home
        // doesn't discard the synced lyrics and reopen the song as "No synced lyrics found".
        if (autoSaveOnExit) saveSync() else onBack()
    }

    // Poll playback position while playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            if (!player.isPlaying && positionMs >= durationMs - 200) { isPlaying = false }
            delay(120)
        }
    }

    val listState = rememberLazyListState()
    // derivedStateOf: the lyric list recomposes only when the highlighted line actually changes, NOT on every
    // 120ms position poll. That per-poll recomposition of every visible line was the source of the scroll jank.
    // MUST be keyed on `lines`: when opening from Home the lyrics load asynchronously, and an unkeyed remember
    // captured the initial EMPTY list forever — the highlight (and therefore the auto-scroll) stayed frozen on
    // the first line for the entire song.
    val currentIndex by remember(lines) {
        derivedStateOf {
            if (lines.isEmpty()) 0
            // Lyrics are neutral, so the slider value is the effective absolute offset applied to the preview.
            else lines.indexOfLast { it.timeMs <= positionMs + offsetMs.roundToInt() }.coerceAtLeast(0)
        }
    }
    // Karaoke-style follow: every highlight change glides the list so the active line settles just above
    // centre. animateScrollBy restarts smoothly from wherever the list currently is (a new line simply
    // retargets the glide), giving one continuous crawl that visibly tracks the song. The old logic only
    // scrolled once the line left a visibility window, which read as "the player isn't following the song"
    // and then lurched a whole window at a time.
    LaunchedEffect(currentIndex, lyricsLoaded) {
        if (lines.isEmpty()) return@LaunchedEffect
        runCatching {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == currentIndex }
            if (item != null) {
                // Glide the line's centre to an anchor ~38% down the viewport (slightly above centre).
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                val anchor = info.viewportStartOffset + viewport * 0.38f
                val delta = item.offset + item.size / 2f - anchor
                listState.animateScrollBy(delta, tween(durationMillis = 600, easing = FastOutSlowInEasing))
            } else {
                // Line is far off-screen (seek, tapped the bar, opened mid-song): land near the anchor
                // directly; subsequent lines glide from there.
                listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) { player.pause(); isPlaying = false }
        else { player.start(); isPlaying = true }
    }

    fun applyOffsetSeek() {
        // Restart ~2.5s earlier so the change is immediately audible/visible, as requested.
        val target = max(0, positionMs - 2500)
        runCatching { player.seekTo(target) }
        positionMs = target
        if (!player.isPlaying) { player.start(); isPlaying = true }
    }

    var timingOpen by remember { mutableStateOf(false) }
    var sourceOpen by remember { mutableStateOf(false) }

    // Lyrics source panel, as on the Now Playing page: the lyrics the player opened with, the best match online, or
    // one source only. A pick replaces the lyrics on screen for previewing (timing back to zero, since another
    // source has its own timing); nothing is written until Apply.
    var source by remember(filePath) { mutableStateOf<PlayerSource>(PlayerSource.Original) }
    var bestFrom by remember(filePath) { mutableStateOf<Providers?>(null) }
    var searching by remember { mutableStateOf(false) }
    var sourceMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun pickSource(choice: PlayerSource) {
        sourceOpen = false
        if (choice == source && !searching && sourceMessage == null) return
        searchJob?.cancel()
        source = choice
        sourceMessage = null
        searching = false
        if (choice == PlayerSource.Original) {
            val original = originalLrc.orEmpty()
            onlineFrom = null
            lrcText = original
            val tag = parseOffsetTagMs(original)
            offsetMs = (if (tag != 0) tag else cachedOffsetMs).toFloat().coerceIn(-OFFSET_RANGE_MS, OFFSET_RANGE_MS)
            return
        }
        val find = onFindLyrics ?: return
        val provider = (choice as? PlayerSource.One)?.provider
        searching = true
        searchJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { find(provider, durationMs.toLong()) } }
            searching = false
            val found = result.getOrNull()
            when {
                result.exceptionOrNull() is UnknownHostException ->
                    sourceMessage = context.getString(R.string.live_lyrics_no_connection)
                found == null -> sourceMessage = provider
                    ?.let { context.getString(R.string.live_lyrics_not_found_on, it.displayName) }
                    ?: context.getString(R.string.live_lyrics_not_found)
                !found.synced -> sourceMessage = context.getString(R.string.player_only_plain_on, found.provider.displayName)
                else -> {
                    if (provider == null) bestFrom = found.provider
                    onlineFrom = found.provider
                    lrcText = found.text
                    offsetMs = parseOffsetTagMs(found.text).toFloat().coerceIn(-OFFSET_RANGE_MS, OFFSET_RANGE_MS)
                }
            }
        }
    }

    BackPressHandler(enabled = autoSaveOnExit && !menuExpanded && !showThumbnailPicker) { exitPlayer() }
    // Registered after the exit handler, so Back closes an open panel first.
    BackHandler(enabled = timingOpen || sourceOpen) { timingOpen = false; sourceOpen = false }

    // The page takes its colours from the song's cover, like the Now Playing page.
    ArtworkBackdrop(palette) { colors ->
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val image = remember(cover) { cover?.asImageBitmap() }
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = stringResource(R.string.album_cover),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(songName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { exitPlayer() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Find lyrics online") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = { menuExpanded = false; onFindOnline() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.embed_lyrics_in_file)) },
                            leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                            onClick = { menuExpanded = false; embedTunedLyrics() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove current lyrics") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                runCatching { File(filePath.substringBeforeLast('.') + ".lrc").delete() }
                                // Mark the song as having no lyrics straight away so Home shows a red note and
                                // tapping it opens the finder (not this player) — no async disk-scan race.
                                SongCache.update(filePath) { prev ->
                                    (prev ?: SongCacheEntry(LyricState.NO_LYRICS)).copy(state = LyricState.NO_LYRICS, offsetMs = 0)
                                }
                                runCatching { player.pause() }
                                onBack()
                            }
                        )
                        if (onRequestCovers != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (hasCover == false) R.string.add_thumbnail else R.string.change_thumbnail)) },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showThumbnailPicker = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!lyricsLoaded) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (searching) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.live_lyrics_finding),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (sourceMessage != null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.MusicOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            sourceMessage.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (!originalLrc.isNullOrBlank()) {
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(onClick = { pickSource(PlayerSource.Original) }) {
                                Text(stringResource(if (autoSaveOnExit) R.string.source_picked else R.string.source_saved))
                            }
                        }
                    }
                } else if (lines.isEmpty()) {
                    Text(
                        "No synced lyrics found for this song.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(lines.size) { i ->
                            val isCurrent = i == currentIndex
                            val color by animateColorAsState(
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                label = "lineColor"
                            )
                            // Emphasis via graphicsLayer scale (no relayout) + a constant font size, so the
                            // current line grows smoothly instead of forcing a text re-measure each change.
                            val scale by animateFloatAsState(if (isCurrent) 1.12f else 1f, label = "lineScale")
                            Text(
                                text = lines[i].text.ifBlank { "♪" },
                                color = color,
                                fontSize = 21.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                lineHeight = 30.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = scale; scaleY = scale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .clickable {
                                        // tap a line to jump the track to it (lyrics neutral -> absolute offset)
                                        val seekTo = max(0, lines[i].timeMs.toInt() - offsetMs.roundToInt())
                                        runCatching { player.seekTo(seekTo) }
                                        positionMs = seekTo
                                        if (!player.isPlaying) { player.start(); isPlaying = true }
                                    }
                            )
                        }
                        // Where the lyrics on screen came from, as on the Now Playing page.
                        val from = onlineFrom
                        if (from != null || !autoSaveOnExit) {
                            item {
                                Text(
                                    text = from?.let { stringResource(R.string.player_source_preview, it.displayName) }
                                        ?: stringResource(R.string.live_lyrics_source_file),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    // Timing pops up from its button in the controls, out of the way until the lyrics need nudging.
                    PopupPanel(
                        side = PanelSide.Start,
                        visible = timingOpen,
                        onDismiss = { timingOpen = false },
                        icon = Icons.Filled.Timer,
                        title = stringResource(R.string.timing_title),
                        headerAction = { TimingResetButton(offsetMs) { offsetMs = 0f; applyOffsetSeek() } },
                    ) {
                        TimingPanel(
                            offsetMs = offsetMs,
                            // Stable, absolute range so a saved offset shows at its true position (e.g. -3.9s sits
                            // left of centre) instead of always appearing centred.
                            rangeMs = OFFSET_RANGE_MS,
                            onOffsetChange = { offsetMs = it },
                            onCommit = { applyOffsetSeek() },
                            hint = stringResource(R.string.timing_hint_library),
                        )
                    }
                }
                PopupPanel(
                    side = PanelSide.End,
                    visible = sourceOpen,
                    onDismiss = { sourceOpen = false },
                    icon = Icons.Filled.Lyrics,
                    title = stringResource(R.string.source_title),
                ) {
                    if (!originalLrc.isNullOrBlank()) {
                        SourceRow(
                            label = stringResource(if (autoSaveOnExit) R.string.source_picked else R.string.source_saved),
                            detail = null,
                            selected = source == PlayerSource.Original,
                            onClick = { pickSource(PlayerSource.Original) },
                        )
                    }
                    SourceRow(
                        label = stringResource(R.string.source_auto),
                        detail = bestFrom?.takeIf { source == PlayerSource.Best }?.displayName,
                        selected = source == PlayerSource.Best,
                        onClick = { pickSource(PlayerSource.Best) },
                    )
                    sourceProviders.forEach { provider ->
                        SourceRow(
                            label = provider.displayName,
                            detail = null,
                            selected = (source as? PlayerSource.One)?.provider == provider,
                            onClick = { pickSource(PlayerSource.One(provider)) },
                        )
                    }
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // seek bar
                WaveScrubber(
                    value = positionMs.toFloat(),
                    onValueChange = {
                        positionMs = it.roundToInt()
                        runCatching { player.seekTo(positionMs) }
                    },
                    valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
                    playing = isPlaying,
                    activeColor = colors.vivid,
                    secondaryColor = colors.normal,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(positionMs.toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(fmt(durationMs.toLong()), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(8.dp))

                // Equal-weight side boxes flank the play/pause button so it sits exactly at the bottom-centre.
                // Left: lyrics timing and rewind 5s. Right: the blue "Apply" button that saves the sync.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        PanelButton(
                            icon = Icons.Filled.Timer,
                            contentDescription = stringResource(R.string.timing_title),
                            open = timingOpen,
                            onClick = { sourceOpen = false; timingOpen = !timingOpen },
                            badge = offsetMs.takeIf { it.roundToInt() != 0 }?.let(::formatOffsetSeconds),
                            enabled = lines.isNotEmpty() && !searching && sourceMessage == null,
                        )
                        IconButton(onClick = {
                            val t = max(0, positionMs - 5000); runCatching { player.seekTo(t) }; positionMs = t
                        }) {
                            Icon(Icons.Filled.Replay, contentDescription = "Back 5s", modifier = Modifier.size(28.dp))
                        }
                    }
                    FilledIconButton(
                        onClick = { togglePlay() },
                        modifier = Modifier.size(64.dp),
                        colors = artworkPlayButtonColors(colors.vivid),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onFindLyrics != null) {
                            PanelButton(
                                icon = Icons.Filled.Lyrics,
                                contentDescription = stringResource(R.string.source_title),
                                open = sourceOpen,
                                onClick = { timingOpen = false; sourceOpen = !sourceOpen },
                                highlighted = source != PlayerSource.Original,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Button(
                            onClick = { saveSync() },
                            enabled = !searching && sourceMessage == null,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1565C0),
                                contentColor = Color.White,
                            )
                        ) {
                            Text(stringResource(R.string.apply))
                        }
                    }
                }
            }
        }
    }

    }

    if (showThumbnailPicker && onRequestCovers != null) {
        ThumbnailPickerDialog(
            onRequestCovers = onRequestCovers,
            onPick = { url ->
                scope.launch(Dispatchers.IO) {
                    val ok = embedCoverInFile(context, filePath, url)
                    withContext(Dispatchers.Main) {
                        if (ok) coverVersion++
                        showToast(context, context.getString(if (ok) R.string.thumbnail_saved else R.string.error))
                    }
                }
            },
            onDismiss = { showThumbnailPicker = false },
        )
    }
}
