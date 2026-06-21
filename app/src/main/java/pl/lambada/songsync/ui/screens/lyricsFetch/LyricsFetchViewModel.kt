package pl.lambada.songsync.ui.screens.lyricsFetch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.lambada.songsync.R
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.MatchConfig
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.ui.LocalSong
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.embedLyricsInFile
import pl.lambada.songsync.util.ext.getVersion
import pl.lambada.songsync.util.generateLrcContent
import pl.lambada.songsync.util.isLegacyFileAccessRequired
import pl.lambada.songsync.util.newLyricsFilePath
import pl.lambada.songsync.util.saveToExternalPath
import pl.lambada.songsync.util.showToast
import java.net.UnknownHostException

/**
 * ViewModel class for the main functionality of the app.
 */
class LyricsFetchViewModel(
    val source: LocalSong?,
    val userSettingsController: UserSettingsController,
    private val lyricsProviderService: LyricsProviderService
) : ViewModel() {
    var querySongName by mutableStateOf(source?.songName ?: "")
    var queryArtistName by mutableStateOf(source?.artists ?: "")

    // queryStatus: "Not submitted", "Pending", "Success", "Failed" - used to show different UI
    var queryState by mutableStateOf(
        if (source == null) QueryStatus.NotSubmitted else QueryStatus.Pending
    )
    private var queryOffset by mutableIntStateOf(0)
    var lrcOffset by mutableIntStateOf(0)

    /** The provider that actually produced the shown lyrics (may differ from the chosen one after fallback). */
    var activeProvider by mutableStateOf(userSettingsController.selectedProvider)

    var lyricsFetchState by mutableStateOf<LyricsFetchState>(LyricsFetchState.NotSubmitted)

    private suspend fun getSyncedLyrics(title: String, artist: String): String? =
        lyricsProviderService.getSyncedLyrics(
            title,
            artist,
            userSettingsController.selectedProvider,
            userSettingsController.includeTranslation,
            userSettingsController.includeRomanization,
            userSettingsController.multiPersonWordByWord,
            userSettingsController.unsyncedFallbackMusixmatch
        )

    private val matcher = SmartLyricsMatcher(providerService = lyricsProviderService)

    fun loadSongInfo(context: Context, tryingAgain: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            queryState = QueryStatus.Pending
            lyricsFetchState = LyricsFetchState.NotSubmitted

            try {
                // Clean the (often messy) tags/filename into proper query candidates, score every provider hit
                // by title/artist/duration, and pick the best -- the same smart engine the batch uses. This is
                // what makes a junk tag like "Kendrick Lamar - HUMBLE. / KendrickLamarVEVO" actually resolve,
                // and stops a wrong "first result" (e.g. a random remix) from being accepted.
                val durationSec = source?.filePath?.let { readDurationSeconds(context, it) }
                val local = LocalTrack(querySongName, queryArtistName, durationSec)
                val candidates = FilenameParser.candidates(querySongName, queryArtistName, source?.filePath)
                    .ifEmpty { listOf(QueryCandidate(querySongName, queryArtistName ?: "", MatchStrategy.TAGS)) }

                // Selected provider first, then the rest as automatic fallback.
                val order = (listOf(userSettingsController.selectedProvider) +
                        Providers.entries.filter { it != userSettingsController.selectedProvider }).distinct()

                val hits = matcher.search(local, candidates, MatchConfig(providerOrder = order))
                val best = hits.firstOrNull { it.tier != MatchTier.REJECT } ?: hits.firstOrNull()
                    ?: run {
                        queryState = QueryStatus.Failed(Exception("No provider had matching synced lyrics."))
                        return@launch
                    }

                val lyrics = matcher.fetchLyrics(best)
                if (lyrics.isNullOrBlank()) {
                    queryState = QueryStatus.Failed(Exception("Found a match but no synced lyrics."))
                    return@launch
                }

                activeProvider = best.provider
                queryState = QueryStatus.Success(
                    SongInfo(songName = best.result.title, artistName = best.result.artist)
                )
                lyricsFetchState = LyricsFetchState.Success(lyrics)
            } catch (e: UnknownHostException) {
                queryState = QueryStatus.NoConnection
            } catch (e: Exception) {
                queryState = QueryStatus.Failed(e)
            }
        }
    }

    /** Switch provider from the single-song screen (tapping the provider label) and re-fetch with it first. */
    fun changeProvider(provider: Providers, context: Context) {
        userSettingsController.updateSelectedProviders(provider)
        activeProvider = provider
        loadSongInfo(context, tryingAgain = false)
    }

    fun saveLyricsToFile(
        lyrics: String,
        song: SongInfo,
        filePath: String?,
        context: Context,
        generatedUsingString: String
    ) {
        val lrcContent = generateLrcContent(song, lyrics, generatedUsingString, lrcOffset, userSettingsController.directlyModifyTimestamps)
        val file = newLyricsFilePath(filePath, song)

        if (!isLegacyFileAccessRequired(filePath)) {
            file.writeText(lrcContent)
        } else {
            saveToExternalPath(
                context = context,
                sourceFilePath = filePath,
                lrc = lrcContent,
                fileName = file.name,
                newLyricsFilePath = userSettingsController.sdCardPath
            )
        }

        showToast(context, R.string.file_saved_to, file.absolutePath)
    }

    private fun loadLyrics(title: String, artist: String) {
        viewModelScope.launch {
            lyricsFetchState = LyricsFetchState.Pending

            try {
                val lyrics = getSyncedLyrics(
                    title,
                    artist
                ) ?: throw NullPointerException("Lyrics result is null")

                lyricsFetchState = LyricsFetchState.Success(lyrics)
            } catch (e: Exception) {
                lyricsFetchState = LyricsFetchState.Failed(e)
            }
        }
    }

    fun embedLyrics(
        lyrics: String,
        filePath: String?,
        context: Context,
        song: SongInfo
    ) {
        val lrcContent = generateLrcContent(song, lyrics, context.getString(R.string.generated_using), lrcOffset, userSettingsController.directlyModifyTimestamps)

        runCatching {
            embedLyricsInFile(
                context = context,
                filePath = if (filePath != null && filePath.isNotEmpty()) filePath else throw NullPointerException("File path is null"),
                lrcContent
            )
        }.onFailure { exception ->
            showToast(context, resolveEmbedErrorMessage(context, exception))
        }.onSuccess {
            showToast(context, R.string.embedded_lyrics_in_file)
        }
    }

    fun fetchLyricsInLanguage(songId: Long?, language: String) {
        if (songId == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            lyricsFetchState = LyricsFetchState.Pending
            
            try {
                val lyrics = lyricsProviderService.getLyricsInLanguage(songId, language)
                if (lyrics != null) {
                    lyricsFetchState = LyricsFetchState.Success(lyrics)
                    
                    // Update the currentLanguage in the song info
                    if (queryState is QueryStatus.Success) {
                        val currentSong = (queryState as QueryStatus.Success).song
                        queryState = QueryStatus.Success(
                            currentSong.copy(currentLanguage = language)
                        )
                    }
                } else {
                    lyricsFetchState = LyricsFetchState.Failed(Exception("No lyrics found for this language"))
                }
            } catch (e: Exception) {
                lyricsFetchState = LyricsFetchState.Failed(e)
            }
        }
    }
}

/** Reads the audio file's duration in seconds (for confidence scoring); null if it can't be read. */
private fun readDurationSeconds(context: Context, filePath: String): Double? = runCatching {
    val mmr = android.media.MediaMetadataRetriever()
    mmr.setDataSource(filePath)
    val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    mmr.release()
    ms?.let { it / 1000.0 }
}.getOrNull()

private fun resolveEmbedErrorMessage(context: Context, exception: Throwable): String {
    return when (exception) {
        is NullPointerException -> context.getString(R.string.embed_non_local_song_error)
        else -> exception.message ?: context.getString(R.string.error)
    }
}

sealed interface LyricsFetchState {
    data object NotSubmitted : LyricsFetchState
    data object Pending : LyricsFetchState
    data class Success(val lyrics: String) : LyricsFetchState
    data class Failed(val exception: Exception) : LyricsFetchState
}

sealed interface QueryStatus {
    data object NotSubmitted : QueryStatus
    data object Pending : QueryStatus
    data class Success(val song: SongInfo) : QueryStatus
    data class Failed(val exception: Exception) : QueryStatus
    data object NoConnection : QueryStatus
}