package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import pl.lambada.songsync.data.remote.lyrics_providers.apple.AppleAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LastResortAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.QQMusicAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyLyricsAPI
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.InternalErrorException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.Providers
import java.io.FileNotFoundException
import java.net.UnknownHostException

/**
 * Service class for interacting with different lyrics providers.
 */
class LyricsProviderService {
    // Spotify API token
    private val spotifyAPI = SpotifyAPI()

    // Spotify Track Url
    private var spotifyUrl = ""

    // LRCLib Track ID
    private var lrcLibID = 0

    // QQMusic request payload
    private var qqPayload = ""

    // Netease Track ID and stuff
    private var neteaseID = 0L
    
    // Apple API
    private val appleAPI = AppleAPI()
    
    // Apple Track ID
    private var appleID = 0L

    // Internal last-resort canonicalizer (iTunes/Deezer) — also a source of extra cover art.
    private val lastResortAPI = LastResortAPI()

    /**
     * Refreshes the access token by sending a request to the Spotify API.
     */
    suspend fun refreshSpotifyToken() = kotlin.runCatching {
        spotifyAPI.refreshToken()
    }

    /**
     * Gets song information from the Spotify API.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @param offset (optional) The offset used for trying to find a better match or searching again.
     * @return The SongInfo object containing the song information.
     */
    @Throws(
        UnknownHostException::class,
        FileNotFoundException::class,
        NoTrackFoundException::class,
        EmptyQueryException::class,
        InternalErrorException::class
    )
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0, provider: Providers): SongInfo? {
        return try {
            when (provider) {
                Providers.SPOTIFY -> spotifyAPI.getSongInfo(query, offset).also {
                    spotifyUrl = it?.songLink ?: ""
                } ?: throw NoTrackFoundException()
                
                Providers.LRCLIB -> LRCLibAPI().getSongInfo(query, offset).also {
                    lrcLibID = it?.lrcLibID ?: 0
                } ?: throw NoTrackFoundException()

                Providers.NETEASE -> NeteaseAPI().getSongInfo(query, offset).also {
                    neteaseID = it?.neteaseID ?: 0
                } ?: throw NoTrackFoundException()

                Providers.QQMUSIC -> QQMusicAPI().getSongInfo(query, offset).also {
                    qqPayload = it?.qqPayload ?: ""
                } ?: throw NoTrackFoundException()

                Providers.APPLE -> appleAPI.getSongInfo(query, offset).also {
                    appleID = it?.appleID ?: 0
                } ?: throw NoTrackFoundException()
            }
        } catch (e: Exception) {
            when (e) {
                is InternalErrorException, is NoTrackFoundException, is EmptyQueryException -> throw e
                else -> throw InternalErrorException(Log.getStackTraceString(e))
            }
        }
    }

    /**
     * Gathers album-cover URLs for a song from every provider that exposes artwork (Apple across a few search
     * results, Spotify), so the user can pick one in the thumbnail picker. Best-effort and failure-tolerant:
     * a provider that errors or has no art simply contributes nothing. Exact-duplicate URLs are removed.
     */
    suspend fun getCoverCandidates(title: String, artist: String, max: Int = 9): List<String> {
        val urls = LinkedHashSet<String>()

        suspend fun collect(provider: Providers, offsets: IntRange) {
            for (offset in offsets) {
                if (urls.size >= max) return
                val cover = runCatching { getSongInfo(SongInfo(title, artist), offset, provider) }
                    .getOrNull()?.albumCoverLink?.takeIf { it.isNotBlank() }
                if (cover != null) urls.add(cover)
            }
        }

        runCatching { refreshSpotifyToken() }
        collect(Providers.APPLE, 0..3)
        collect(Providers.SPOTIFY, 0..0)

        // Top up with iTunes/Deezer artwork so there are still options when Apple/Spotify came up short.
        if (urls.size < max) {
            runCatching { lastResortAPI.covers(title, artist, max) }
                .getOrDefault(emptyList())
                .forEach { if (urls.size < max) urls.add(it) }
        }

        return urls.toList()
    }

    /**
     * Gets synced lyrics using the song link and returns them as a string formatted as an LRC file.
     * @param songLink The link to the song.
     * @return The synced lyrics as a string.
     */
    suspend fun getSyncedLyrics(
        songTitle: String,
        artistName: String,
        provider: Providers,
        // TODO providers could be a sealed interface to include such parameters
        includeTranslationNetEase: Boolean = false,
        includeRomanizationNetEase: Boolean = false,
        multiPersonWordByWord: Boolean = false,
    ): String? {
        return when (provider) {
            Providers.SPOTIFY -> SpotifyLyricsAPI().getSyncedLyrics(spotifyUrl)
            Providers.LRCLIB -> LRCLibAPI().getSyncedLyrics(lrcLibID)
            Providers.NETEASE -> NeteaseAPI().getSyncedLyrics(
                neteaseID, includeTranslationNetEase, includeRomanizationNetEase
            )

            Providers.QQMUSIC -> QQMusicAPI().getSyncedLyrics(qqPayload, multiPersonWordByWord)

            Providers.APPLE -> appleAPI.getSyncedLyrics(
                appleID, multiPersonWordByWord
            )
        }
    }
}
