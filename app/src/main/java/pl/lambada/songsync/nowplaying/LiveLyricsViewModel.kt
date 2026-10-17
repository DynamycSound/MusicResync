package pl.lambada.songsync.nowplaying

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.util.EmbeddedLyrics
import pl.lambada.songsync.util.LyricLine
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.lrcToPlainText
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.matching.TextMatch
import pl.lambada.songsync.util.parseSyncedLrc
import java.io.File
import java.net.UnknownHostException

/** What the live lyrics view shows for the current track. */
sealed interface LiveLyrics {
    data object Searching : LiveLyrics

    /** [source] is the provider the lyrics came from, or null when they come from the song's own file. */
    data class Synced(val lines: List<LyricLine>, val source: Providers?) : LiveLyrics
    data class Plain(val text: String, val source: Providers?) : LiveLyrics

    /** [provider] is set when the user picked one source and it has nothing for this song. */
    data class NotFound(val provider: Providers? = null) : LiveLyrics
    data object NoConnection : LiveLyrics
}

/**
 * Finds lyrics for whatever another app is playing, for the live (read-only, follows the player) lyrics view.
 * A library file's own lyrics win; otherwise the same matching engine as the search screen runs in fast mode, since
 * the song is already playing. The user can also pick one source by hand. Nothing is written anywhere.
 */
class LiveLyricsViewModel(
    private val userSettingsController: UserSettingsController,
    lyricsProviderService: LyricsProviderService,
) : ViewModel() {

    var lyrics by mutableStateOf<LiveLyrics>(LiveLyrics.Searching)
        private set

    /** The source the user picked from the side panel, or null for the automatic best match. */
    var pinnedProvider by mutableStateOf<Providers?>(null)
        private set

    /** Sources the user can pick from: their enabled providers, in their order. */
    val availableProviders: List<Providers>
        get() = (userSettingsController.enabledProviderOrder + userSettingsController.selectedProvider).distinct()

    private val lookup = LyricsLookup(SmartLyricsMatcher(providerService = lyricsProviderService))
    private var currentKey: String? = null
    private var job: Job? = null
    private var lastTrack: NowPlayingTrack? = null
    private var lastLocalPath: String? = null

    /** Results per track and source, so skipping back to a song (or re-entering the view) doesn't search again. */
    private val results = HashMap<String, LiveLyrics>()

    private fun keyOf(track: NowPlayingTrack, localPath: String?, provider: Providers?) =
        listOf(track.packageName, track.title, track.artist, localPath, provider?.name).joinToString("|")

    /** Shows lyrics from [provider] only (null: back to the automatic best match) for the current song. */
    fun pickProvider(provider: Providers?) {
        if (provider == pinnedProvider) return
        pinnedProvider = provider
        lastTrack?.let { load(it, lastLocalPath) }
    }

    /** Searches again for the current song and source. */
    fun retry() {
        lastTrack?.let { load(it, lastLocalPath, retry = true) }
    }

    /** Starts finding lyrics for [track] unless they are already shown or being found; [retry] forces a new search. */
    fun load(track: NowPlayingTrack, localPath: String?, retry: Boolean = false) {
        // A picked source only applies to its song: the next song starts on the automatic match again.
        val previous = lastTrack
        if (previous != null && (previous.packageName != track.packageName || previous.title != track.title ||
                previous.artist != track.artist)
        ) pinnedProvider = null
        lastTrack = track
        lastLocalPath = localPath

        val provider = pinnedProvider
        val key = keyOf(track, localPath, provider)
        if (!retry && key == currentKey) return
        currentKey = key
        job?.cancel()
        if (!retry) results[key]?.let { lyrics = it; return }

        lyrics = LiveLyrics.Searching
        job = viewModelScope.launch(Dispatchers.IO) {
            val found = try {
                if (provider == null) find(track, localPath) else findOn(provider, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                LiveLyrics.NoConnection
            } catch (e: Exception) {
                Log.w(TAG, "Live lyrics search failed for \"${track.title}\"", e)
                LiveLyrics.NotFound(provider)
            }
            if (found != LiveLyrics.NoConnection) results[key] = found
            if (currentKey == key) lyrics = found
        }
    }

    private suspend fun find(track: NowPlayingTrack, localPath: String?): LiveLyrics {
        localPath?.let { path -> fileLyrics(path)?.let { return it } }

        val (local, candidates) = queryFor(track)
        val order = listOf(userSettingsController.selectedProvider) + userSettingsController.enabledProviderOrder
        return lookup.best(local, candidates, order)?.toLiveLyrics() ?: LiveLyrics.NotFound()
    }

    /** Lyrics from one source only, for when the user picks it: its best synced match, else its plain text. */
    private suspend fun findOn(provider: Providers, track: NowPlayingTrack): LiveLyrics {
        val (local, candidates) = queryFor(track)
        return lookup.on(provider, local, candidates)?.toLiveLyrics() ?: LiveLyrics.NotFound(provider)
    }

    private fun FoundLyrics.toLiveLyrics(): LiveLyrics? =
        if (synced) parseSyncedLrc(text).takeIf { it.isNotEmpty() }?.let { LiveLyrics.Synced(it, provider) }
        else lrcToPlainText(text).takeIf { it.isNotBlank() }?.let { LiveLyrics.Plain(it, provider) }

    /** The song as the matcher sees it, plus the readings of its title/artist to search for. */
    private fun queryFor(track: NowPlayingTrack): Pair<LocalTrack, List<QueryCandidate>> {
        val query = NowPlayingLogic.searchQuery(track.title, track.artist)
        // The cleaned reading first, then the player's raw title/artist as a fallback reading.
        val candidates = (FilenameParser.candidates(query.title, query.artist, null) +
            FilenameParser.candidates(track.title, track.artist, null))
            .distinctBy { TextMatch.normalizeForCompare(it.title) + "|" + TextMatch.normalizeForCompare(it.artist) }
            .ifEmpty { listOf(QueryCandidate(query.title, query.artist, MatchStrategy.TAGS)) }
        val local = LocalTrack(
            title = query.title,
            artist = query.artist.takeIf { !TextMatch.isJunkArtist(it) },
            durationSec = track.durationMs?.let { it / 1000.0 },
            album = track.album,
        )
        return local to candidates
    }

    /** Lyrics saved for a library song: its .lrc next to the file, else the ones embedded in its tags. */
    private fun fileLyrics(path: String): LiveLyrics? {
        val text = runCatching { File(path.substringBeforeLast('.') + ".lrc").takeIf { it.exists() }?.readText() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { EmbeddedLyrics.read(path) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val synced = parseSyncedLrc(text)
        if (synced.isNotEmpty()) return LiveLyrics.Synced(synced, null)
        return lrcToPlainText(text).takeIf { it.isNotBlank() }?.let { LiveLyrics.Plain(it, null) }
    }

    private companion object {
        const val TAG = "LiveLyrics"
    }
}
