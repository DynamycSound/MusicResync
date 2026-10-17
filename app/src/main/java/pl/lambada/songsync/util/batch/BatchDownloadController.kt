package pl.lambada.songsync.util.batch

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.util.PendingPlainLyrics
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.downloadLyrics
import pl.lambada.songsync.util.ext.toLrcFile
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.SongMatchInfo
import pl.lambada.songsync.util.persistLyrics
import java.util.concurrent.ConcurrentHashMap

/**
 * App-scoped host for the batch lyrics download. The batch used to live in the Home screen's ViewModel scope,
 * which tied its life to a navigation entry and to the process staying foreground. Here it runs in its own
 * supervisor scope, survives every navigation, and is kept alive by [BatchDownloadService] (a foreground
 * service with a progress notification) while the user leaves the app.
 *
 * All UI state is Compose snapshot state so the batch screen and the Home rows observe it directly.
 */
object BatchDownloadController {

    enum class Status { IDLE, RUNNING, DONE, RATE_LIMITED, CANCELLED }

    /** One finished song of the current run, in processing order. Drives the history pager. */
    data class ProcessedSong(val song: Song, val info: SongMatchInfo)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    var status by mutableStateOf(Status.IDLE)
        private set
    val isRunning get() = status == Status.RUNNING

    var total by mutableIntStateOf(0)
        private set
    var currentSong by mutableStateOf<Song?>(null)
        private set

    /** Finished songs of this run, oldest first. */
    val processed = mutableStateListOf<ProcessedSong>()
    private val processedIndexByPath = HashMap<String, Int>()

    var syncedCount by mutableIntStateOf(0); private set
    var unsyncedCount by mutableIntStateOf(0); private set
    var noLyricsCount by mutableIntStateOf(0); private set
    var failedCount by mutableIntStateOf(0); private set
    var skippedCount by mutableIntStateOf(0); private set

    /** Songs dropped because the user skipped their folder mid-run (issue #22). Not in [processed]: no page each. */
    var folderSkippedCount by mutableIntStateOf(0); private set

    /** Songs the run is done with: finished ones plus the ones dropped with a skipped folder. */
    val processedCount get() = processed.size + folderSkippedCount

    // --- "Skip this folder" (issue #22) ---------------------------------------------------------------------
    // Concurrency: the engine is ONE sequential coroutine on Dispatchers.IO; the UI calls skipFolder() on the
    // main thread. The engine only reads the concurrent set below (checked right before each song starts), so a
    // skip takes effect from the next queued song on. The in-flight song is never interrupted: it finishes and
    // is recorded normally, which rules out half-written .lrc files or tags.

    /** File paths of this run's queue, in processing order. Written only in [start] (main thread). */
    private var queuePaths: List<String?> = emptyList()

    /** Index in the queue of the song the engine reached last (started or skipped); -1 before the first. */
    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Read by the engine thread. Holds normalized folder paths (see [FolderSkip]). */
    private val skippedFolderSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The same folders as Compose state, for the UI. Written on the main thread only. */
    var skippedFolders by mutableStateOf<Set<String>>(emptySet())
        private set

    /** How many songs of [folder] are still queued after the one being processed now. */
    fun remainingInFolder(folder: String): Int =
        FolderSkip.remainingInFolder(queuePaths, currentIndex + 1, folder)

    /**
     * Skips every song of [folder] that has not started yet, for this run only. The song in progress finishes
     * normally. No-op when no run is active. Call from the main thread.
     */
    fun skipFolder(folder: String) {
        if (!isRunning) return
        val normalized = FolderSkip.normalize(folder)
        if (normalized.isEmpty()) return
        skippedFolderSet.add(normalized)
        skippedFolders = skippedFolders + normalized
    }

    /**
     * Plain lyrics that were FOUND for songs with no synced match but not saved (fallback toggle off).
     * The batch screen shows the count and offers "Add all".
     */
    val pendingUnsynced = mutableStateListOf<PendingPlainLyrics>()

    /** Once the user taps "Add all", newly found plain lyrics are saved immediately for the rest of the run. */
    var autoAddUnsynced by mutableStateOf(false)
        private set

    /** Set by the Home screen so live results also recolor its rows. */
    var onSongResultListener: ((filePath: String, info: SongMatchInfo) -> Unit)? = null
    var onMetadataCorrectedListener: ((filePath: String, title: String?, artist: String?) -> Unit)? = null

    /** Wall-clock bounds of the current/last run, for ETA and the "Finished in" line. 0 = not set. */
    var runStartedAt = 0L
        private set
    var runFinishedAt = 0L
        private set

    /** Incremented when something (the notification) asks the UI to open the batch screen. */
    var openRequests by mutableIntStateOf(0)
        private set

    fun requestOpen() { openRequests++ }

    /** Progress feed for the foreground service notification. */
    data class Progress(
        val done: Int,
        val total: Int,
        val currentTitle: String?,
        val finished: Boolean = false,
        /** Part of [done] dropped with a skipped folder (instant, so excluded from the pace estimate). */
        val folderSkipped: Int = 0,
    )

    private val _progress = MutableStateFlow(Progress(0, 0, null))
    val progressFlow: StateFlow<Progress> = _progress

    // Snapshot of the run options needed by "Add all unsynced".
    private var runSaveLrc = true
    private var runEmbedLyrics = false
    private var runSettings: UserSettingsController? = null

    fun start(context: Context, songs: List<Song>, settings: UserSettingsController) {
        if (isRunning) return
        val appContext = context.applicationContext

        status = Status.RUNNING
        total = songs.size
        currentSong = null
        processed.clear()
        processedIndexByPath.clear()
        pendingUnsynced.clear()
        autoAddUnsynced = settings.batchAddUnsyncedFallback
        syncedCount = 0; unsyncedCount = 0; noLyricsCount = 0; failedCount = 0; skippedCount = 0
        clearFolderSkips()
        queuePaths = songs.map { it.filePath }
        runSaveLrc = settings.batchSaveLrc
        runEmbedLyrics = settings.batchEmbedLyrics
        runSettings = settings
        runStartedAt = System.currentTimeMillis()
        runFinishedAt = 0L
        _progress.value = Progress(0, songs.size, null)

        // Foreground service + notification from the very start, so the run is already protected while the
        // user follows the "keep it alive" steps or leaves the app.
        startService(appContext)

        job = scope.launch {
            try {
                downloadLyrics(
                    songs = songs,
                    settings = settings,
                    context = appContext,
                    correctMetadata = settings.batchCorrectMetadata,
                    skipExisting = settings.batchSkipExisting,
                    autoTryProviders = settings.batchAutoTryProviders,
                    saveLrc = runSaveLrc,
                    embedLyrics = runEmbedLyrics,
                    addUnsyncedFallback = { autoAddUnsynced },
                    onPlainAvailable = { pending ->
                        // Deduplicate by path (a song is only processed once per run, but be safe).
                        if (pendingUnsynced.none { it.song.filePath == pending.song.filePath }) {
                            pendingUnsynced.add(pending)
                        }
                    },
                    shouldSkipSong = { song -> FolderSkip.isSkipped(song.filePath, skippedFolderSet) },
                    onSongSkipped = {
                        // Left untouched: no lookup, no write, no cached-state change, no history page.
                        // currentSong stays on the last real song until the next one starts (microseconds).
                        currentIndex++
                        folderSkippedCount++
                        publishProgress(currentSong?.title)
                    },
                    onSongStarted = { song ->
                        currentIndex++
                        currentSong = song
                        song.filePath?.let { onSongResultListener?.invoke(it, SongMatchInfo(LyricState.FETCHING)) }
                        publishProgress(song.title)
                    },
                    onSongResult = { song, info -> recordResult(song, info) },
                    onDownloadComplete = { status = Status.DONE },
                    onRateLimitReached = { status = Status.RATE_LIMITED },
                    onMetadataCorrected = { path, title, artist ->
                        onMetadataCorrectedListener?.invoke(path, title, artist)
                    },
                )
            } finally {
                SongCache.flush()
                if (status == Status.RUNNING) status = Status.CANCELLED
                currentSong = null
                runFinishedAt = System.currentTimeMillis()
                _progress.value = _progress.value.copy(
                    done = processedCount, folderSkipped = folderSkippedCount, finished = true
                )
            }
        }
    }

    private fun publishProgress(currentTitle: String?) {
        _progress.value = Progress(processedCount, total, currentTitle, folderSkipped = folderSkippedCount)
    }

    private fun clearFolderSkips() {
        folderSkippedCount = 0
        currentIndex = -1
        queuePaths = emptyList()
        skippedFolderSet.clear()
        skippedFolders = emptySet()
    }

    private fun recordResult(song: Song, info: SongMatchInfo) {
        val path = song.filePath
        when (info.state) {
            LyricState.SYNCED, LyricState.REVIEW -> syncedCount++
            LyricState.UNSYNCED -> unsyncedCount++
            LyricState.HAS_LYRICS -> skippedCount++ // only the skip path reports HAS_LYRICS during a run
            LyricState.NO_LYRICS -> noLyricsCount++
            LyricState.FAILED -> failedCount++
            LyricState.FETCHING -> return
        }
        if (path != null) {
            processedIndexByPath[path] = processed.size
            SongCache.setStateDeferred(path, info)
            onSongResultListener?.invoke(path, info)
        }
        processed.add(ProcessedSong(song, info))
        publishProgress(currentSong?.title)
    }

    /**
     * Saves every pending plain-lyrics hit found so far and keeps saving new ones for the rest of the run.
     * Runs in the controller scope so it works even while the batch is still going, and never blocks the UI.
     */
    fun addAllPendingUnsynced(context: Context, onFinished: (added: Int) -> Unit = {}) {
        autoAddUnsynced = true
        val appContext = context.applicationContext
        val toAdd = pendingUnsynced.toList()
        if (toAdd.isEmpty()) { onFinished(0); return }
        scope.launch {
            var added = 0
            toAdd.forEach { p ->
                val path = p.song.filePath ?: return@forEach
                val saved = runCatching {
                    persistLyrics(
                        appContext, p.song, path.toLrcFile(), p.lrcContent,
                        saveLrc = runSaveLrc, embedLyrics = runEmbedLyrics,
                        sdCardPath = runSettings?.sdCardPath,
                    )
                }.getOrDefault(false)
                if (!saved) return@forEach

                added++
                val info = SongMatchInfo(
                    LyricState.UNSYNCED,
                    provider = p.provider,
                    matchedTitle = p.matchedTitle,
                    matchedArtist = p.matchedArtist,
                )
                // Rewrite this run's history entry and its counters: the song moves from "not found" to "unsynced".
                processedIndexByPath[path]?.let { idx ->
                    if (idx in processed.indices && processed[idx].song.filePath == path) {
                        processed[idx] = processed[idx].copy(info = info)
                    }
                }
                noLyricsCount = (noLyricsCount - 1).coerceAtLeast(0)
                unsyncedCount++
                SongCache.setStateDeferred(path, info)
                onSongResultListener?.invoke(path, info)
            }
            pendingUnsynced.removeAll(toAdd)
            SongCache.flush()
            onFinished(added)
        }
    }

    /** Stops the run. The engine checks for cancellation between songs, so nothing else gets written. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Clears a finished run's state so the next batch starts fresh. No-op while running. */
    fun reset() {
        if (isRunning) return
        status = Status.IDLE
        total = 0
        currentSong = null
        processed.clear()
        processedIndexByPath.clear()
        pendingUnsynced.clear()
        syncedCount = 0; unsyncedCount = 0; noLyricsCount = 0; failedCount = 0; skippedCount = 0
        clearFolderSkips()
        _progress.value = Progress(0, 0, null)
    }

    private fun startService(appContext: Context) {
        val intent = Intent(appContext, BatchDownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
        }
    }
}
