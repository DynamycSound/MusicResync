package pl.lambada.songsync.ui.screens.home

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.util.fastMapNotNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import pl.lambada.songsync.R
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.SortOrders
import pl.lambada.songsync.domain.model.SortValues
import pl.lambada.songsync.util.EmbeddedLyrics
import pl.lambada.songsync.util.batch.BatchDownloadController
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.matching.LrcPrescan
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.PrescanResult
import pl.lambada.songsync.util.matching.SongMatchInfo

/**
 * ViewModel class for the main functionality of the app.
 */
class HomeViewModel(
    val userSettingsController: UserSettingsController,
    private val lyricsProviderService: LyricsProviderService
) : ViewModel() {
    var cachedSongs: List<Song>? = null
    val selectedSongs = mutableStateListOf<String>()
    var allSongs by mutableStateOf<List<Song>?>(null)

    /** Per-song match outcome keyed by filePath, populated during and after batch sync. Drives row colour. */
    val songMatchStatus = mutableStateMapOf<String, SongMatchInfo>()

    /** Currently selected tab: All / Has Lyrics / No Lyrics. */
    var selectedTab by mutableStateOf(LyricsTab.ALL)

    /**
     * Resolved lyric state for a song. Pure map read — NO file I/O — so it's safe to call from composition for
     * every row/badge without jank. The map is seeded once on load (off the main thread, from the pre-scan
     * results) and updated as the batch runs.
     */
    fun lyricStateFor(song: Song): LyricState =
        songMatchStatus[song.filePath]?.state ?: LyricState.NO_LYRICS

    private fun songHasLyrics(song: Song): Boolean = when (lyricStateFor(song)) {
        // UNSYNCED (plain .lrc, no timestamps) deliberately counts as "no lyrics": it won't scroll with the
        // music, so it stays red/searchable until replaced with a synced version.
        LyricState.HAS_LYRICS, LyricState.SYNCED, LyricState.REVIEW -> true
        else -> false
    }

    /**
     * Songs to show for the active tab (applied on top of the existing search/folder filters).
     * derivedStateOf so the 9000-song filter runs once per actual data change, not on every
     * recomposition read — a plain getter re-filtered the whole library each frame.
     */
    val tabFilteredSongs: List<Song> by derivedStateOf {
        when (selectedTab) {
            LyricsTab.ALL -> displaySongs
            LyricsTab.HAS_LYRICS -> displaySongs.filter { songHasLyrics(it) }
            LyricsTab.NO_LYRICS -> displaySongs.filterNot { songHasLyrics(it) }
        }
    }

    /** Cached "has lyrics" count so the tab row doesn't rescan the whole library on every recomposition. */
    private val hasLyricsCount by derivedStateOf { displaySongs.count { songHasLyrics(it) } }

    fun countFor(tab: LyricsTab): Int = when (tab) {
        LyricsTab.ALL -> displaySongs.size
        LyricsTab.HAS_LYRICS -> hasLyricsCount
        LyricsTab.NO_LYRICS -> displaySongs.size - hasLyricsCount
    }

    var isRefreshing by mutableStateOf(false)

    /**
     * True only for the very first lyric-state population on a cacheless launch. Until the initial disk pre-scan
     * finishes, the app would otherwise render every song as red/"No lyrics" because missing map entries fall
     * back to NO_LYRICS. We keep showing the loading screen for that first cold-start scan instead of lying.
     */
    var waitingForInitialLyricScan by mutableStateOf(false)
        private set

    /** True while a batch download is running, gates the disk refresh so it can't clobber in-flight states. */
    val batchRunning: Boolean
        get() = BatchDownloadController.isRunning

    var searchQuery by mutableStateOf("")

    // Filter settings
    private var cachedFolders: MutableList<String>? = null
    private var hideFolders = userSettingsController.blacklistedFolders.isNotEmpty()

    // filtered folders/lyrics songs
    private var _cachedFilteredSongs = MutableStateFlow<List<Song>>(emptyList())

    // searching
    private var _searchResults = MutableStateFlow<List<Song>>(emptyList())

    var displaySongs by mutableStateOf(
        when {
            searchQuery.isNotEmpty() -> _searchResults.value
            _cachedFilteredSongs.value.isNotEmpty() -> _cachedFilteredSongs.value
            else -> allSongs ?: listOf()
        }
    )

    var showFilters by mutableStateOf(false)
    var showSort by mutableStateOf(false)
    var showingSearch by  mutableStateOf(false)
    var showSearch by mutableStateOf(showingSearch)

    val songsToBatchDownload by derivedStateOf {
        val base = if (selectedSongs.isEmpty())
            // No explicit selection -> operate on exactly what the user currently sees, which includes the active
            // tab filter (All / Has lyrics / No lyrics), not just the search/folder-filtered displaySongs.
            tabFilteredSongs
        else
            (allSongs ?: listOf()).filter { selectedSongs.contains(it.filePath) }.toList()

        // "Skip songs with no lyrics" (on by default): drop songs a previous run already searched and came up
        // empty for, so a rerun doesn't keep asking the providers for songs that have nothing. The marker is a
        // NO_LYRICS state WITH a non-empty failedProviders list (set only when a real fetch ran) - this excludes
        // songs the disk pre-scan merely seeded as NO_LYRICS but never actually tried, so a first batch still runs
        // them. FAILED (network/IO errors) is deliberately left in so those get retried. Filtering the shared list
        // here keeps the options-dialog count and the actual run in sync.
        if (userSettingsController.batchSkipNoLyrics)
            base.filterNot { song ->
                val info = songMatchStatus[song.filePath]
                info?.state == LyricState.NO_LYRICS && info.failedProviders.isNotEmpty()
            }
        else
            base
    }

    init {
        viewModelScope.launch { updateSongsToDisplay() }
        // Mirror live batch results (which now run app-scoped, not in this ViewModel) into the row-state map
        // so the list recolors while the batch runs, exactly as it did when the batch lived here.
        BatchDownloadController.onSongResultListener = { path, info -> songMatchStatus[path] = info }
        BatchDownloadController.onMetadataCorrectedListener = { path, title, artist ->
            refreshSongMetadata(path, title, artist)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun updateSongsToDisplay() = coroutineScope {
        snapshotFlow { allSongs }
            .filterNotNull()
            // simple .combine wasn't enough apparently, so im using this
            .flatMapLatest { all ->
                _cachedFilteredSongs.combine(_searchResults) { filtered, searchResults ->
                    when {
                        searchQuery.isNotEmpty() -> searchResults
                        filtered.isNotEmpty() -> filtered
                        else -> all
                    }
                }
            }.collect { newDisplaySongs ->
                displaySongs = newDisplaySongs
            }
    }

    /** The sort the current cachedSongs list was built with, so re-entering Home doesn't reload for nothing. */
    private var appliedSort: Pair<SortValues, SortOrders>? = null

    /**
     * Loads the library only when actually needed: first composition, or a real sort change. Home's
     * LaunchedEffect re-fires every time the screen re-enters composition (back gesture from Settings/search,
     * returning to the app), and unconditionally nulling cachedSongs there re-ran the full MediaStore query and
     * disk re-scan on every back navigation — the main source of the "app freezes after going back" reports.
     */
    fun ensureSongsLoaded(context: Context, sortBy: SortValues, sortOrder: SortOrders) {
        val sort = sortBy to sortOrder
        if (appliedSort == sort && cachedSongs != null) return
        appliedSort = sort
        cachedSongs = null
        updateAllSongs(context, sortBy, sortOrder)
    }

    fun updateAllSongs(context: Context, sortBy: SortValues, sortOrder: SortOrders) = viewModelScope.launch(Dispatchers.IO) {
        appliedSort = sortBy to sortOrder
        // getAllSongs seeds row state instantly from the persistent cache, so the list renders immediately.
        allSongs = getAllSongs(context, sortBy, sortOrder)
        // Then verify against disk and replace the cached state with the fresh truth. Awaited so this job only
        // completes once the disk re-scan is done — pull-to-refresh joins it to time the spinner to real work.
        refreshLyricStatesFromDisk()?.join()
    }

    /**
     * Re-derives lyric state from disk for all loaded songs (off the main thread). Called when Home resumes so a
     * row reflects lyrics just saved on the fetch screen, or a sidecar .lrc added/removed outside the app.
     * No-op while a batch is running so it can't clobber in-flight FETCHING/SYNCED states.
     */
    /**
     * Fast, no-disk re-seed of row state from the persistent cache (e.g. after the player saved/removed lyrics).
     * Runs on the main thread on every Home resume, so it only writes entries whose STATE actually changed:
     * blindly rewriting all ~N entries flooded the snapshot system with invalidations (each write re-triggers the
     * full-library tab filter) and also clobbered richer post-batch info (confidence badge) with the bare cached
     * state.
     */
    fun reseedFromCache() {
        val songs = cachedSongs ?: return
        songs.forEach { s ->
            val path = s.filePath ?: return@forEach
            SongCache.matchInfo(path)?.let {
                if (songMatchStatus[path]?.state != it.state) songMatchStatus[path] = it
            }
        }
    }

    private var diskRefreshJob: kotlinx.coroutines.Job? = null

    fun refreshLyricStatesFromDisk(): kotlinx.coroutines.Job? {
        if (batchRunning) return null // don't clobber in-flight FETCHING/SYNCED states while a batch is saving
        // Coalesce: rapid back-and-forth navigation used to pile up several concurrent full-library disk scans.
        diskRefreshJob?.takeIf { it.isActive }?.let { return it }
        val songs = cachedSongs ?: return null
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Sidecar .lrc first, then lyrics embedded in the audio tags (issue #5): embedded-only songs
                // used to show as "missing lyrics" because only sibling files were checked.
                val results = LrcPrescan.scan(songs.mapNotNull { it.filePath }, EmbeddedLyrics::read)
                val states = results.mapValues { (_, r) ->
                    when (r) {
                        PrescanResult.ALREADY_SYNCED, PrescanResult.RENAMED_FROM_PRIVATE,
                        PrescanResult.EMBEDDED_SYNCED -> LyricState.HAS_LYRICS
                        PrescanResult.ALREADY_PRESENT_UNSYNCED, PrescanResult.EMBEDDED_UNSYNCED -> LyricState.UNSYNCED
                        PrescanResult.NONE -> LyricState.NO_LYRICS
                    }
                }
                states.forEach { (path, st) ->
                    val prev = songMatchStatus[path]
                    // Only write real changes. A fresh SYNCED/REVIEW result from the batch coarsens to
                    // HAS_LYRICS on disk, so treat those as already-correct instead of downgrading the row
                    // (which dropped the confidence badge) and re-invalidating every list item on each resume.
                    // Also keep FAILED: the disk scan can only see "no .lrc here", not why. A row that failed
                    // during a batch stays marked as failed (distinct icon) instead of flattening to "no lyrics".
                    val equivalent = prev?.state == st ||
                        (st == LyricState.HAS_LYRICS && (prev?.state == LyricState.SYNCED || prev?.state == LyricState.REVIEW)) ||
                        (st == LyricState.NO_LYRICS && prev?.state == LyricState.FAILED)
                    if (!equivalent) songMatchStatus[path] = SongMatchInfo(st)
                }
                // Persist the fresh truth (keeping each song's remembered offset/provider) for an instant next launch.
                SongCache.replaceStates(states)
            } finally {
                waitingForInitialLyricScan = false
            }
        }
        diskRefreshJob = job
        return job
    }

    /**
     * Loads all songs from the MediaStore.
     * @param context The application context.
     * @return A list of Song objects representing the songs.
     */
    private fun getAllSongs(context: Context, sortBy: SortValues, sortOrder: SortOrders): List<Song> {
        return cachedSongs ?: run {
            // Rebuilding the song list (cachedSongs was invalidated, e.g. a sort change or refresh) makes the
            // derived folder list stale too — drop it so getSongFolders re-derives from the fresh songs instead
            // of serving folders for a library that may have changed.
            cachedFolders = null
            val selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0"
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM,
            )
            val sortOrder = sortBy.name + " " + sortOrder.queryName

            val songs = mutableListOf<Song>()
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use {
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

                while (it.moveToNext()) {
                    val title = it.getString(titleColumn).let { str ->
                        if (str == "<unknown>") null else str
                    }
                    val artist = it.getString(artistColumn).let { str ->
                        if (str == "<unknown>") null else str
                    }
                    val albumId = it.getLong(albumIdColumn)
                    val filePath = it.getString(pathColumn)
                    val durationMs = it.getLong(durationColumn).takeIf { d -> d > 0 }
                    val album = it.getString(albumColumn).let { str ->
                        if (str.isNullOrBlank() || str == "<unknown>") null else str
                    }

                    @Suppress("SpellCheckingInspection")
                    val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                    val imgUri = ContentUris.withAppendedId(
                        sArtworkUri,
                        albumId
                    )

                    val song = Song(title, artist, imgUri, filePath, durationMs, album)
                    songs.add(song)
                }
            }
            cursor?.close()

            // MusicResync: seed lyric state instantly from the persistent cache (no disk scan) so the list and
            // its green/red notes render immediately on launch. updateAllSongs then verifies against disk in the
            // background (refreshLyricStatesFromDisk) and replaces this with the fresh truth.
            runCatching {
                SongCache.init(context)
                songMatchStatus.clear()
                songs.forEach { s ->
                    val path = s.filePath ?: return@forEach
                    SongCache.matchInfo(path)?.let { songMatchStatus[path] = it }
                }
                // Cacheless first launch: keep the loading screen up until the first disk pre-scan populates the
                // map, otherwise every missing entry falls back to NO_LYRICS and the whole list flashes red.
                waitingForInitialLyricScan = songs.isNotEmpty() && songMatchStatus.isEmpty()
            }

            cachedSongs = songs
            viewModelScope.launch { filterSongs() }
            cachedSongs!!
        }
    }

    /**
     * Updates song search (filter) results based on the query.
     * @param query The search query.
     */
    fun updateSearchResults(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (query.isEmpty()) {
                _searchResults.value = _cachedFilteredSongs.value
                return@launch
            }

            val data: List<Song> = when {
                _cachedFilteredSongs.value.isNotEmpty() -> _cachedFilteredSongs.value
                cachedSongs != null -> cachedSongs!!
                else -> { return@launch }
            }

            val results = data.filter {
                it.title?.contains(query, ignoreCase = true) == true ||
                it.artist?.contains(query, ignoreCase = true) == true
            }

            _searchResults.value = results
        }
    }

    /**
     * Loads all songs' folders
     * @param context The application context.
     * @return A list of folders.
     */
    fun getSongFolders(context: Context): List<String> {
        return cachedFolders ?: run {
            val folders = mutableListOf<String>()

            for (song in getAllSongs(context, SortValues.TITLE, SortOrders.ASCENDING)) {
                val path = song.filePath
                val folder = path?.substring(0, path.lastIndexOf("/"))
                if (folder != null && !folders.contains(folder))
                    folders.add(folder)
            }

            cachedFolders = folders
            cachedFolders!!
        }
    }

    /**
     * Filter songs based on user's preferences.
     * @return A list of songs depending on the user's preferences. If no preferences are set, null is returned, so app will use all songs.
     */
    fun filterSongs() = viewModelScope.launch(Dispatchers.IO) {
        hideFolders = userSettingsController.blacklistedFolders.isNotEmpty()
        val songs = cachedSongs ?: run { _cachedFilteredSongs.value = emptyList(); return@launch }

        // "Hide songs with lyrics" filters on the resolved lyric STATE (seeded from cache, refreshed off-disk),
        // not on raw .lrc file existence. This keeps it consistent with the tabs and songHasLyrics(): a plain
        // (UNSYNCED) .lrc counts as "no real synced lyrics", so such a song is NOT hidden and still shows under
        // "No lyrics". It also drops the per-song disk stat that used to run here.
        fun notBlacklisted(song: Song): Boolean {
            val path = song.filePath ?: return true
            val folder = path.substring(0, path.lastIndexOf("/"))
            return !userSettingsController.blacklistedFolders.contains(folder)
        }

        _cachedFilteredSongs.value = when {
            userSettingsController.hideLyrics && hideFolders ->
                songs.filter { !songHasLyrics(it) && notBlacklisted(it) }

            userSettingsController.hideLyrics ->
                songs.filter { !songHasLyrics(it) }

            hideFolders ->
                songs.filter { notBlacklisted(it) }

            else -> emptyList()
        }
    }

    fun invertSongSelection() = viewModelScope.launch {
        val newSelectedSongs = displaySongs.filter { it.filePath !in selectedSongs }
        selectedSongs.clear()
        selectedSongs.addAll(newSelectedSongs.mapNotNull { it.filePath })
    }

    fun selectAllDisplayingSongs() = viewModelScope.launch {
        selectedSongs.clear()
        selectedSongs.addAll(displaySongs.fastMapNotNull { it.filePath })
    }

    fun onHideLyricsChange(newHideLyrics: Boolean) {
        userSettingsController.updateHideLyrics(newHideLyrics)
    }

    fun onToggleFolderBlacklist(folder: String, blacklisted: Boolean) {
        if (blacklisted) {
            userSettingsController.updateBlacklistedFolders(
                userSettingsController.blacklistedFolders + folder
            )
        } else {
            userSettingsController.updateBlacklistedFolders(
                userSettingsController.blacklistedFolders - folder
            )
        }
    }

    suspend fun getSongInfo(query: SongInfo): SongInfo? =
        lyricsProviderService.getSongInfo(query, provider = userSettingsController.selectedProvider)

    suspend fun getSyncedLyrics(title: String, artist: String): String? {
        return try {
            // Self-contained two-step: resolve the SongInfo (with its provider token) and immediately fetch its
            // lyrics from that same object, so nothing relies on shared provider-service state.
            val provider = userSettingsController.selectedProvider
            val info = lyricsProviderService.getSongInfo(SongInfo(title, artist), provider = provider) ?: return null
            lyricsProviderService.getSyncedLyrics(
                info,
                provider = provider,
                includeTranslationNetEase = userSettingsController.includeTranslation,
                includeRomanizationNetEase = userSettingsController.includeRomanization,
                multiPersonWordByWord = userSettingsController.multiPersonWordByWord,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun selectSong(song: Song, newValue: Boolean) {
        if (newValue) {
            song.filePath?.let { selectedSongs.add(it) }
            showSearch = false
            showingSearch = false
        } else {
            selectedSongs.remove(song.filePath)

            if (selectedSongs.size == 0 && searchQuery.isNotEmpty())
                showingSearch = true // show again but don't focus
        }
    }

    /**
     * Starts the batch in the app-scoped [BatchDownloadController] (its own supervisor scope plus a foreground
     * service), so it keeps running across navigation and while the app is in the background.
     */
    fun startBatchDownload(context: Context) {
        BatchDownloadController.reset()
        BatchDownloadController.start(context, songsToBatchDownload, userSettingsController)
    }

    /** Stops an in-progress batch download immediately (the loop checks for cancellation between songs). */
    fun cancelBatch() = BatchDownloadController.stop()

    /**
     * Patches a song's title/artist in every in-memory list the UI reads, so a row refreshes immediately after
     * "Correct the metadata" rewrites its tags — instead of staying on the stale value (e.g. "Unknown") until the
     * MediaStore is re-scanned on a later cold start. Call on the main thread.
     */
    fun refreshSongMetadata(filePath: String, title: String?, artist: String?) {
        if (title.isNullOrBlank() && artist.isNullOrBlank()) return
        fun Song.patch(): Song =
            if (this.filePath != filePath) this
            else copy(
                title = title?.takeIf { it.isNotBlank() } ?: this.title,
                artist = artist?.takeIf { it.isNotBlank() } ?: this.artist,
            )
        cachedSongs = cachedSongs?.map { it.patch() }
        _cachedFilteredSongs.value = _cachedFilteredSongs.value.map { it.patch() }
        _searchResults.value = _searchResults.value.map { it.patch() }
        displaySongs = displaySongs.map { it.patch() }
        allSongs = allSongs?.map { it.patch() }
    }

}

/** The three top-level tabs the song list can be filtered by. */
enum class LyricsTab(val titleRes: Int) {
    ALL(R.string.tab_all),
    HAS_LYRICS(R.string.tab_has_lyrics),
    NO_LYRICS(R.string.tab_no_lyrics),
}