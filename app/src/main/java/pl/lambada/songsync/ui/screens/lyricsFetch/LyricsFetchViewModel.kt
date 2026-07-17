package pl.lambada.songsync.ui.screens.lyricsFetch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.MatchConfig
import pl.lambada.songsync.data.remote.lyrics_providers.ScoredHit
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.ui.LocalSong
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.cache.SongCache
import pl.lambada.songsync.util.cache.SongCacheEntry
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.QueryCandidate
import com.kyant.taglib.TagLib
import pl.lambada.songsync.util.embedCoverInFile
import pl.lambada.songsync.util.embedLyricsInFile
import pl.lambada.songsync.util.ext.getVersion
import pl.lambada.songsync.util.generateLrcContent
import pl.lambada.songsync.util.getFileDescriptorFromPath
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

    /** Live provider status shown while searching. Providers are now queried in parallel, so this stays null
     *  (the screen shows the generic "Searching…" label) rather than naming one provider misleadingly. */
    var searchStatus by mutableStateOf<Providers?>(null)

    /** Per-provider probe result for the cloud-icon dropdown: tried→HAS_SYNCED/NONE, or LOADING during a retry. */
    val providerProbes = mutableStateMapOf<Providers, ProviderProbe>()

    var lyricsFetchState by mutableStateOf<LyricsFetchState>(LyricsFetchState.NotSubmitted)

    /** Cleaned local path used both for saving the .lrc and as the persistent cache key. */
    private val cachePath: String? get() = source?.filePath?.replace(".nowplaying", "")

    /** True once this screen has actually persisted lyrics/cover changes, so backing out should no longer warn. */
    var hasPersistedLyricsChange by mutableStateOf(false)
        private set

    private fun rememberProviders(chosen: Providers?, failed: List<Providers>) {
        val path = cachePath ?: return
        val prev = SongCache.get(path) ?: return
        SongCache.put(
            path,
            prev.copy(
                chosenProvider = chosen?.displayName ?: prev.chosenProvider,
                failedProviders = failed.map { it.displayName },
            )
        )
    }

    private suspend fun getSyncedLyrics(title: String, artist: String): String? {
        // Resolve then fetch from the same SongInfo so the provider token never comes from shared state.
        val provider = userSettingsController.selectedProvider
        val info = lyricsProviderService.getSongInfo(SongInfo(title, artist), provider = provider) ?: return null
        return lyricsProviderService.getSyncedLyrics(
            info,
            provider,
            userSettingsController.includeTranslation,
            userSettingsController.includeRomanization,
            userSettingsController.multiPersonWordByWord,
        )
    }

    private val matcher = SmartLyricsMatcher(providerService = lyricsProviderService)

    fun loadSongInfo(context: Context, tryingAgain: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            queryState = QueryStatus.Pending
            lyricsFetchState = LyricsFetchState.NotSubmitted
            hasPersistedLyricsChange = false
            providerProbes.clear()

            try {
                // Clean the (often messy) tags/filename into proper query candidates, score every provider hit
                // by title/artist/duration, and pick the best -- the same smart engine the batch uses. This is
                // what makes a junk tag like "Kendrick Lamar - HUMBLE. / KendrickLamarVEVO" actually resolve,
                // and stops a wrong "first result" (e.g. a random remix) from being accepted.
                val durationSec = source?.filePath?.let { readDurationSeconds(context, it) }
                val local = LocalTrack(querySongName, queryArtistName, durationSec)
                val candidates = FilenameParser.candidates(querySongName, queryArtistName, source?.filePath)
                    .ifEmpty { listOf(QueryCandidate(querySongName, queryArtistName ?: "", MatchStrategy.TAGS)) }

                // Selected provider first, then the user's configured fallback chain (Settings > Provider order).
                // Providers the user disabled are not queried automatically (they stay available via the
                // per-provider dropdown/retry).
                val order = (listOf(userSettingsController.selectedProvider) +
                        userSettingsController.enabledProviderOrder).distinct()

                // Track which providers the search actually reached. All providers now run in parallel, but the
                // auto-accept early stop can still cancel slower ones mid-flight — those are genuinely UNTRIED,
                // not failures, and must not be painted with a red X.
                val attempted = linkedSetOf<Providers>()
                val hits = matcher.search(
                    local, candidates, MatchConfig(providerOrder = order),
                    onAttempt = { provider -> attempted.add(provider); providerProbes[provider] = ProviderProbe.LOADING },
                    onSkipped = { provider -> attempted.remove(provider) },
                )
                searchStatus = null

                // Fall through every acceptable hit until one actually returns synced lyrics (a provider can
                // match the metadata yet have no synced body -- the old code gave up at the first such hit).
                val ranked = hits.filter { it.tier != MatchTier.REJECT }
                var chosen: ScoredHit? = null
                var lyrics: String? = null
                for (hit in ranked) {
                    val l = runCatching { matcher.fetchLyrics(hit) }.getOrNull()
                    if (!l.isNullOrBlank()) { chosen = hit; lyrics = l; break }
                }

                // Record per-provider probe outcomes for the cloud-icon dropdown: the winner is HAS_SYNCED,
                // providers we actually reached but that had nothing are NONE, and everything past the early stop
                // stays UNTRIED instead of being mislabelled as failed.
                order.forEach { p ->
                    providerProbes[p] = when {
                        p == chosen?.provider -> ProviderProbe.HAS_SYNCED
                        p in attempted -> ProviderProbe.NONE
                        else -> ProviderProbe.UNTRIED
                    }
                }

                if (chosen != null && lyrics != null) {
                    activeProvider = chosen.provider
                    rememberProviders(chosen.provider, order.filter { it != chosen.provider })
                    queryState = QueryStatus.Success(
                        SongInfo(songName = chosen.result.title, artistName = chosen.result.artist)
                    )
                    lyricsFetchState = LyricsFetchState.Success(lyrics)
                    return@launch
                }

                // Last resort: canonicalize a messy filename via iTunes/Deezer and retry LRCLib under the clean
                // name. This path is only allowed to succeed when the rescue still clears the scorer-based gates;
                // otherwise we fail honestly instead of silently substituting a different song.
                val lr = runCatching { matcher.lastResort(local, candidates) }.getOrNull()
                if (lr != null && lr.synced) {
                    activeProvider = Providers.LRCLIB
                    providerProbes[Providers.LRCLIB] = ProviderProbe.HAS_SYNCED
                    rememberProviders(Providers.LRCLIB, order.filter { it != Providers.LRCLIB })
                    queryState = QueryStatus.Success(SongInfo(songName = lr.title, artistName = lr.artist, albumCoverLink = lr.coverUrl))
                    lyricsFetchState = LyricsFetchState.Success(lr.lyrics)
                    return@launch
                }

                // No synced lyrics anywhere -> offer plain (LRCLib first, then the last-resort's plain body) instead of erroring.
                rememberProviders(null, order)
                val plain = runCatching { matcher.fetchPlainLyrics(local, candidates) }.getOrNull()
                val plainLyrics = plain?.plainLyrics ?: lr?.takeUnless { it.synced }?.lyrics
                queryState = QueryStatus.SyncedNotFound(
                    song = SongInfo(
                        songName = plain?.result?.title ?: lr?.title ?: ranked.firstOrNull()?.result?.title ?: querySongName,
                        artistName = plain?.result?.artist ?: lr?.artist ?: ranked.firstOrNull()?.result?.artist ?: queryArtistName,
                    ),
                    plainLyrics = plainLyrics,
                )
            } catch (e: UnknownHostException) {
                searchStatus = null
                queryState = QueryStatus.NoConnection
            } catch (e: Exception) {
                searchStatus = null
                queryState = QueryStatus.Failed(e)
            }
        }
    }

    /** Re-fetch a single provider (tapped in the cloud dropdown), showing a spinner on that row while it runs. */
    fun retryProvider(provider: Providers, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            providerProbes[provider] = ProviderProbe.LOADING
            try {
                val durationSec = source?.filePath?.let { readDurationSeconds(context, it) }
                val local = LocalTrack(querySongName, queryArtistName, durationSec)
                val candidates = FilenameParser.candidates(querySongName, queryArtistName, source?.filePath)
                    .ifEmpty { listOf(QueryCandidate(querySongName, queryArtistName ?: "", MatchStrategy.TAGS)) }

                val hits = matcher.search(local, candidates, MatchConfig(providerOrder = listOf(provider)))
                val ranked = hits.filter { it.tier != MatchTier.REJECT }
                var chosen: ScoredHit? = null
                var lyrics: String? = null
                for (hit in ranked) {
                    val l = runCatching { matcher.fetchLyrics(hit) }.getOrNull()
                    if (!l.isNullOrBlank()) { chosen = hit; lyrics = l; break }
                }

                if (lyrics != null && chosen != null) {
                    providerProbes[provider] = ProviderProbe.HAS_SYNCED
                    activeProvider = provider
                    userSettingsController.updateSelectedProviders(provider)
                    rememberProviders(provider, providerProbes.filterValues { it == ProviderProbe.NONE }.keys.toList())
                    queryState = QueryStatus.Success(
                        SongInfo(songName = chosen.result.title, artistName = chosen.result.artist)
                    )
                    lyricsFetchState = LyricsFetchState.Success(lyrics)
                } else {
                    providerProbes[provider] = ProviderProbe.NONE
                }
            } catch (e: Exception) {
                providerProbes[provider] = ProviderProbe.NONE
            }
        }
    }

    /** Switch provider from the single-song screen (tapping the provider label) and re-fetch with it first. */
    fun changeProvider(provider: Providers, context: Context) {
        userSettingsController.updateSelectedProviders(provider)
        activeProvider = provider
        loadSongInfo(context, tryingAgain = false)
    }

    /** Whether the local file already has embedded artwork — drives the "Add" vs "Change" thumbnail label.
     *  Null until checked. */
    var localHasCover by mutableStateOf<Boolean?>(null)

    fun checkLocalCover(context: Context) {
        val path = cachePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val has = runCatching {
                val fd = getFileDescriptorFromPath(context, path, "r") ?: return@runCatching false
                TagLib.getFrontCover(fd.dup().detachFd()) != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { localHasCover = has }
        }
    }

    /** Album-cover candidates from the providers, for the thumbnail picker. */
    suspend fun fetchCoverCandidates(song: SongInfo): List<String> =
        lyricsProviderService.getCoverCandidates(
            song.songName ?: querySongName,
            song.artistName ?: queryArtistName,
            source?.filePath,
        )

    fun markLyricsWillBeSavedByPlayer() {
        hasPersistedLyricsChange = true
    }

    /** Downloads and embeds the chosen cover into the local audio file (off the main thread). */
    fun embedCover(url: String, context: Context) {
        val path = cachePath ?: run {
            showToast(context, context.getString(R.string.embed_non_local_song_error)); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Network download + TagLib write stay on IO; the UI-state flip and the Toast must run on Main.
            // showToast calls Toast.show() directly, which crashes ("can't toast on a thread that has not called
            // Looper.prepare()") when invoked from an IO worker thread.
            val ok = embedCoverInFile(context, path, url)
            withContext(Dispatchers.Main) {
                localHasCover = ok || localHasCover == true
                showToast(context, context.getString(if (ok) R.string.thumbnail_saved else R.string.error))
            }
        }
    }

    /** Builds the .lrc content WITHOUT writing it — used by "Adjust timing" so nothing is saved until the
     *  user confirms with the checkmark in the player. */
    fun buildLrc(lyrics: String, song: SongInfo, context: Context): String =
        generateLrcContent(song, lyrics, context.getString(R.string.generated_using), lrcOffset, userSettingsController.directlyModifyTimestamps)

    /** Saves plain (unsynced) lyrics chosen from the "no synced lyrics" prompt and records it as UNSYNCED. */
    fun acceptPlainLyrics(plainLyrics: String, song: SongInfo, context: Context) {
        val path = cachePath
        saveLyricsToFile(plainLyrics, song, path, context, context.getString(R.string.generated_using))
        path?.let { p ->
            SongCache.update(p) { prev -> (prev ?: SongCacheEntry(LyricState.UNSYNCED)).copy(state = LyricState.UNSYNCED) }
        }
        hasPersistedLyricsChange = true
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

        // Remember this song now has (synced) lyrics so Home shows a green note on return. acceptPlainLyrics
        // downgrades this to UNSYNCED afterwards for the plain-fallback path.
        cachePath?.let { p ->
            SongCache.update(p) { prev -> (prev ?: SongCacheEntry(LyricState.HAS_LYRICS)).copy(state = LyricState.HAS_LYRICS) }
        }
        hasPersistedLyricsChange = true

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
        }.onSuccess { embedded ->
            // embedLyricsInFile returns false (without throwing) when the tag write fails or is denied — don't
            // claim success in that case.
            if (embedded) {
                hasPersistedLyricsChange = true
                showToast(context, R.string.embedded_lyrics_in_file)
            } else showToast(context, context.getString(R.string.error))
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

    /** A match was found but no provider had synced lyrics. Offer plain lyrics (if any) instead of failing. */
    data class SyncedNotFound(val song: SongInfo, val plainLyrics: String?) : QueryStatus
    data class Failed(val exception: Exception) : QueryStatus
    data object NoConnection : QueryStatus
}

/**
 * Outcome of probing one provider for the current song, shown in the cloud-icon dropdown.
 *  - [LOADING]    a fetch is in flight
 *  - [HAS_SYNCED] this provider produced the synced lyrics
 *  - [NONE]       attempted but had no synced lyrics
 *  - [UNTRIED]    never attempted (e.g. the search auto-accepted earlier and stopped) — NOT a failure
 */
enum class ProviderProbe { LOADING, HAS_SYNCED, NONE, UNTRIED }