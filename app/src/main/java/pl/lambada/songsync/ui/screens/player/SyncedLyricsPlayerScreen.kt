package pl.lambada.songsync.ui.screens.player

import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.delay
import pl.lambada.songsync.R
import pl.lambada.songsync.util.applyOffsetToLyrics
import pl.lambada.songsync.util.buildNeutralLrc
import pl.lambada.songsync.util.parseOffsetTagMs
import pl.lambada.songsync.util.upsertOffsetTag
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.cache.SongCacheEntry
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.showToast
import pl.lambada.songsync.util.toCrlf
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private data class LyricLine(val timeMs: Long, val text: String)

/** Half-width of the stable, absolute offset slider range (±10s). A saved -3.9s sits visibly left of centre. */
private const val OFFSET_RANGE_MS = 10000f

private val timestamp = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{1,3})]""")

private fun parseSyncedLrc(raw: String): List<LyricLine> {
    val out = ArrayList<LyricLine>()
    for (line in raw.lineSequence()) {
        val matches = timestamp.findAll(line).toList()
        if (matches.isEmpty()) continue
        val text = line.substring(matches.last().range.last + 1).trim()
        for (m in matches) {
            val (mm, ss, frac) = m.destructured
            val ms = mm.toLong() * 60_000 + ss.toLong() * 1_000 +
                    (frac.padEnd(3, '0').take(3)).toLong()
            out.add(LyricLine(ms, text))
        }
    }
    return out.sortedBy { it.timeMs }
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
     *  until the user presses the checkmark. Null when opening an already-saved song from Home. */
    initialLyrics: String? = null,
    /** User setting: true bakes the offset into timestamps on save; false writes an `[offset:]` tag instead. */
    directlyModifyTimestamps: Boolean = false,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var lrcText by remember(filePath) {
        mutableStateOf(
            initialLyrics?.takeIf { it.isNotBlank() } ?: runCatching {
                File(filePath.substringBeforeLast('.') + ".lrc").takeIf { it.exists() }?.readText()
            }.getOrNull().orEmpty()
        )
    }
    // Reconcile the two timing models into one neutral form (zero applied offset, no [offset:] tag) so all the
    // preview/seek/save math can treat the user's offset as a single absolute value. A file in tag mode carries
    // [offset:N]; a direct-shifted file has the offset baked into its timestamps and we undo it here.
    val fileOffsetMs = remember(lrcText) { parseOffsetTagMs(lrcText) }
    val cachedOffsetMs = remember(filePath) { SongCache.get(filePath)?.offsetMs ?: 0 }
    val fileUsesOffsetTag = fileOffsetMs != 0
    val baseAppliedOffsetMs = if (fileUsesOffsetTag) fileOffsetMs else cachedOffsetMs
    val neutralLrc = remember(lrcText, baseAppliedOffsetMs, fileUsesOffsetTag) {
        buildNeutralLrc(lrcText, baseAppliedOffsetMs, fileUsesOffsetTag)
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

    // Persists the lyrics with the chosen timing, remembers the total offset for next time, marks the song as
    // having lyrics, and returns to Home (where the row now shows a green note). Always rebuilds from the neutral
    // lyrics (zero applied offset, no [offset:] tag) and applies the absolute slider value once, so re-saving can
    // never double-shift. The on-disk model follows the user's setting:
    //   directlyModifyTimestamps == true  -> bake the offset into the timestamps, no [offset:] tag
    //   directlyModifyTimestamps == false -> keep neutral timestamps + exactly one [offset:] tag
    fun saveSync() {
        val total = offsetMs.roundToInt()
        val file = File(filePath.substringBeforeLast('.') + ".lrc")
        val neutralBase = neutralLrc.ifBlank {
            runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { buildNeutralLrc(it, parseOffsetTagMs(it).let { tag -> if (tag != 0) tag else cachedOffsetMs }, parseOffsetTagMs(it) != 0) }
                .orEmpty()
        }
        if (neutralBase.isBlank()) { onBack(); return }
        runCatching {
            val output = if (directlyModifyTimestamps) {
                // Bake the shift into the timestamps; ensure no stray offset tag remains.
                if (total != 0) applyOffsetToLyrics(neutralBase, total) else neutralBase
            } else {
                // Neutral timestamps + a single offset tag carrying the absolute value.
                upsertOffsetTag(neutralBase, total)
            }
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
    val currentIndex by remember {
        derivedStateOf {
            if (lines.isEmpty()) 0
            // Lyrics are neutral, so the slider value is the effective absolute offset applied to the preview.
            else lines.indexOfLast { it.timeMs <= positionMs + offsetMs.roundToInt() }.coerceAtLeast(0)
        }
    }
    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) runCatching { listState.animateScrollToItem(currentIndex, scrollOffset = -300) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(songName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(artists, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
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
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
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
                    }
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // seek bar
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = {
                        positionMs = it.roundToInt()
                        runCatching { player.seekTo(positionMs) }
                    },
                    valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(positionMs.toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(fmt(durationMs.toLong()), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offset ${if (offsetMs >= 0) "+" else ""}${(offsetMs / 1000).let { "%.1f".format(it) }}s",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 12.dp))
                    Slider(
                        value = offsetMs,
                        onValueChange = { offsetMs = it },
                        onValueChangeFinished = { applyOffsetSeek() },
                        // Stable, absolute range so a saved offset shows at its true position (e.g. -3.9s sits
                        // left of centre) instead of always appearing centred.
                        valueRange = -OFFSET_RANGE_MS..OFFSET_RANGE_MS,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Equal-weight side boxes flank the play/pause button so it sits exactly at the bottom-centre.
                // Left: rewind 5s. Right: the blue "Apply" button that saves the sync (was a tick up top).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        IconButton(onClick = {
                            val t = max(0, positionMs - 5000); runCatching { player.seekTo(t) }; positionMs = t
                        }) {
                            Icon(Icons.Filled.Replay, contentDescription = "Back 5s", modifier = Modifier.size(28.dp))
                        }
                    }
                    FilledIconButton(onClick = { togglePlay() }, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Button(
                            onClick = { saveSync() },
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
