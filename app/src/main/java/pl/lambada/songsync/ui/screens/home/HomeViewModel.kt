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
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.downloadLyrics
import pl.lambada.songsync.util.ext.toLrcFile
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

    /** Songs to show for the active tab (applied on top of the existing search/folder filters). */
    val tabFilteredSongs: List<Song>
        get() = when (selectedTab) {
            LyricsTab.ALL -> displaySongs
            LyricsTab.HAS_LYRICS -> displaySongs.filter { songHasLyrics(it) }
            LyricsTab.NO_LYRICS -> displaySongs.filterNot { songHasLyrics(it) }
        }

    fun countFor(tab: LyricsTab): Int = when (tab) {
        LyricsTab.ALL -> displaySongs.size
        LyricsTab.HAS_LYRICS -> displaySongs.count { songHasLyrics(it) }
        LyricsTab.NO_LYRICS -> displaySongs.count { !songHasLyrics(it) }
    }

    var isRefreshing by mutableStateOf(false)

    /** True while a batch download is running — gates the disk refresh so it can't clobber in-flight states. */
    var batchRunning by mutableStateOf(false)
        private set

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
        if (selectedSongs.isEmpty())
            displaySongs
        else
            (allSongs ?: listOf()).filter { selectedSongs.contains(it.filePath) }.toList()
    }

    init { viewModelScope.launch { updateSongsToDisplay() } }

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

    fun updateAllSongs(context: Context, sortBy: SortValues, sortOrder: SortOrders) = viewModelScope.launch(Dispatchers.IO) {
        // getAllSongs seeds row state instantly from the persistent cache, so the list renders immediately.
        allSongs = getAllSongs(context, sortBy, sortOrder)
        // Then verify against disk in the background and replace the cached state with the fresh truth.
        refreshLyricStatesFromDisk()
    }

    /**
     * Re-derives lyric state from disk for all loaded songs (off the main thread). Called when Home resumes so a
     * row reflects lyrics just saved on the fetch screen, or a sidecar .lrc added/removed outside the app.
     * No-op while a batch is running so it can't clobber in-flight FETCHING/SYNCED states.
     */
    /** Fast, no-disk re-seed of row state from the persistent cache (e.g. after the player saved/removed lyrics). */
    fun reseedFromCache() {
        val songs = cachedSongs ?: return
        songs.forEach { s ->
            val path = s.filePath ?: return@forEach
            SongCache.matchInfo(path)?.let { songMatchStatus[path] = it }
        }
    }

    fun refreshLyricStatesFromDisk() {
        if (batchRunning) return // don't clobber in-flight FETCHING/SYNCED states while a batch is saving
        val songs = cachedSongs ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val results = LrcPrescan.scan(songs.mapNotNull { it.filePath })
            val states = results.mapValues { (_, r) ->
                when (r) {
                    PrescanResult.ALREADY_SYNCED, PrescanResult.RENAMED_FROM_PRIVATE -> LyricState.HAS_LYRICS
                    PrescanResult.ALREADY_PRESENT_UNSYNCED -> LyricState.UNSYNCED
                    PrescanResult.NONE -> LyricState.NO_LYRICS
                }
            }
            states.forEach { (path, st) -> songMatchStatus[path] = SongMatchInfo(st) }
            // Persist the fresh truth (keeping each song's remembered offset/provider) for an instant next launch.
            SongCache.replaceStates(states)
        }
    }

    /**
     * Loads all songs from the MediaStore.
     * @param context The application context.
     * @return A list of Song objects representing the songs.
     */
    private fun getAllSongs(context: Context, sortBy: SortValues, sortOrder: SortOrders): List<Song> {
        return cachedSongs ?: run {
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
    fun filterSongs() = viewModelScope.launch {
        hideFolders = userSettingsController.blacklistedFolders.isNotEmpty()

        when {
            userSettingsController.hideLyrics && hideFolders -> {
                _cachedFilteredSongs.value = cachedSongs!!
                    .filter {
                        it.filePath.toLrcFile()?.exists() != true && !userSettingsController.blacklistedFolders.contains(
                            it.filePath!!.substring(
                                0, it.filePath.lastIndexOf("/")
                            )
                        )
                    }
            }

            userSettingsController.hideLyrics -> {
                _cachedFilteredSongs.value = cachedSongs!!
                    .filter { it.filePath.toLrcFile()?.exists() != true }
            }

            hideFolders -> {
                _cachedFilteredSongs.value = cachedSongs!!.filter {
                    !userSettingsController.blacklistedFolders.contains(
                        it.filePath!!.substring(
                            0,
                            it.filePath.lastIndexOf("/")
                        )
                    )
                }
            }

            else -> {
                _cachedFilteredSongs.value = emptyList()
            }
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
            lyricsProviderService.getSyncedLyrics(
                title,
                artist,
                provider = userSettingsController.selectedProvider,
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

    private var batchJob: kotlinx.coroutines.Job? = null

    fun batchDownloadLyrics(
        context: Context,
        correctMetadata: Boolean = false,
        skipExisting: Boolean = true,
        autoTryProviders: Boolean = true,
        saveLrc: Boolean = true,
        embedLyrics: Boolean = false,
        addUnsyncedFallback: Boolean = false,
        onProgressUpdate: (successCount: Int, noLyricsCount: Int, failedCount: Int) -> Unit,
        onDownloadComplete: () -> Unit,
        onRateLimitReached: () -> Unit
    ): kotlinx.coroutines.Job {
        batchRunning = true
        val job = viewModelScope.launch {
            downloadLyrics(
                songs = songsToBatchDownload,
                viewModel = this@HomeViewModel,
                context = context,
                correctMetadata = correctMetadata,
                skipExisting = skipExisting,
                autoTryProviders = autoTryProviders,
                saveLrc = saveLrc,
                embedLyrics = embedLyrics,
                addUnsyncedFallback = addUnsyncedFallback,
                onProgressUpdate = onProgressUpdate,
                onSongResult = { filePath, info ->
                    songMatchStatus[filePath] = info
                    SongCache.setStateDeferred(filePath, info)
                },
                onDownloadComplete = onDownloadComplete,
                onRateLimitReached = onRateLimitReached,
            )
        }
        batchJob = job
        // Persist whatever was written, whether the batch finished or was cancelled.
        job.invokeOnCompletion {
            SongCache.flush()
            batchRunning = false
        }
        return job
    }

    /** Stops an in-progress batch download immediately (the loop checks for cancellation between songs). */
    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
    }

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