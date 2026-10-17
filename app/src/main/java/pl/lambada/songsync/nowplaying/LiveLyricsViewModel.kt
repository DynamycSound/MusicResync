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
import pl.lambada.songsync.data.remote.lyrics_providers.MatchConfig
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.util.EmbeddedLyrics
import pl.lambada.songsync.util.LyricLine
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.lrcToPlainText
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.matching.TextMatch
import pl.lambada.songsync.util.parseSyncedLrc
import java.io.File
import java.net.UnknownHostException

/** What the live lyrics view shows for the current track. */
sealed interface LiveLyrics {
    data object Searching : LiveLyrics

    /** [source] is the provider name, or null when the lyrics come from the song's own file. */
    data class Synced(val lines: List<LyricLine>, val source: String?) : LiveLyrics
    data class Plain(val text: String, val source: String?) : LiveLyrics
    data object NotFound : LiveLyrics
    data object NoConnection : LiveLyrics
}

/**
 * Finds lyrics for whatever another app is playing, for the live (read-only, follows the player) lyrics view.
 * A library file's own lyrics win; otherwise the same matching engine as the search screen runs in fast mode, since
 * the song is already playing. Nothing is written anywhere.
 */
class LiveLyricsViewModel(
    private val userSettingsController: UserSettingsController,
    lyricsProviderService: LyricsProviderService,
) : ViewModel() {

    var lyrics by mutableStateOf<LiveLyrics>(LiveLyrics.Searching)
        private set

    private val matcher = SmartLyricsMatcher(providerService = lyricsProviderService)
    private var currentKey: String? = null
    private var job: Job? = null

    /** Results per track, so skipping back to a song (or re-entering the view) doesn't search again. */
    private val results = HashMap<String, LiveLyrics>()

    private fun keyOf(track: NowPlayingTrack, localPath: String?) =
        listOf(track.packageName, track.title, track.artist, localPath).joinToString("\u0000")

    /** Starts finding lyrics for [track] unless they are already shown or being found; [retry] forces a new search. */
    fun load(track: NowPlayingTrack, localPath: String?, retry: Boolean = false) {
        val key = keyOf(track, localPath)
        if (!retry && key == currentKey) return
        currentKey = key
        job?.cancel()
        if (!retry) results[key]?.let { lyrics = it; return }

        lyrics = LiveLyrics.Searching
        job = viewModelScope.launch(Dispatchers.IO) {
            val found = try {
                find(track, localPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                LiveLyrics.NoConnection
            } catch (e: Exception) {
                Log.w(TAG, "Live lyrics search failed for \"${track.title}\"", e)
                LiveLyrics.NotFound
            }
            if (found != LiveLyrics.NoConnection) results[key] = found
            if (currentKey == key) lyrics = found
        }
    }

    private suspend fun find(track: NowPlayingTrack, localPath: String?): LiveLyrics {
        localPath?.let { path -> fileLyrics(path)?.let { return it } }

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
        // The matcher reads results in this order, so a slow or failing provider first in line holds up everyone
        // behind it until its timeout. LRCLib answers fast with synced lyrics and a duration to check against;
        // asking it first means a sure LRCLib match shows in a second instead of after the slowest provider.
        val order = (listOf(Providers.LRCLIB, userSettingsController.selectedProvider) +
            userSettingsController.enabledProviderOrder).distinct()
        val config = MatchConfig.fast(order)

        val hits = matcher.search(local, candidates, config).filter { it.tier != MatchTier.REJECT }
        for (hit in hits) {
            val text = runCatching { matcher.fetchLyrics(hit, config) }.getOrNull() ?: continue
            val synced = parseSyncedLrc(text)
            if (synced.isNotEmpty()) return LiveLyrics.Synced(synced, hit.provider.displayName)
        }

        val rescue = runCatching { matcher.lastResort(local, candidates, config) }.getOrNull()
        if (rescue != null && rescue.synced) {
            val synced = parseSyncedLrc(rescue.lyrics)
            if (synced.isNotEmpty()) return LiveLyrics.Synced(synced, Providers.LRCLIB.displayName)
        }

        // No synced lyrics anywhere: unsynced lyrics are still worth reading along to.
        val plain = runCatching { matcher.fetchPlainLyrics(local, candidates, config) }.getOrNull()
        val plainText = plain?.plainLyrics ?: rescue?.takeUnless { it.synced }?.lyrics
        return plainText?.let { lrcToPlainText(it) }?.takeIf { it.isNotBlank() }
            ?.let { LiveLyrics.Plain(it, Providers.LRCLIB.displayName) }
            ?: LiveLyrics.NotFound
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
