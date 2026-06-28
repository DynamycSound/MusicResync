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

    // Apple API
    private val appleAPI = AppleAPI()

    // Internal last-resort canonicalizer (iTunes/Deezer) — also a source of extra cover art.
    private val lastResortAPI = LastResortAPI()

    // NOTE: this service holds NO per-request mutable state. The provider-specific lookup tokens (Spotify link,
    // LRCLib id, Netease id, QQ payload, Apple id) travel on the SongInfo returned by getSongInfo and are passed
    // back explicitly into getSyncedLyrics. The previous design stashed them in shared fields, so two overlapping
    // lookups on the same instance could read each other's token. Keeping the flow stateless removes that race.

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
            // Each provider populates its own lookup token directly on the returned SongInfo (songLink/lrcLibID/
            // neteaseID/qqPayload/appleID), so the caller can hand the same SongInfo back to getSyncedLyrics.
            when (provider) {
                Providers.SPOTIFY -> spotifyAPI.getSongInfo(query, offset) ?: throw NoTrackFoundException()
                Providers.LRCLIB -> LRCLibAPI().getSongInfo(query, offset) ?: throw NoTrackFoundException()
                Providers.NETEASE -> NeteaseAPI().getSongInfo(query, offset) ?: throw NoTrackFoundException()
                Providers.QQMUSIC -> QQMusicAPI().getSongInfo(query, offset) ?: throw NoTrackFoundException()
                Providers.APPLE -> appleAPI.getSongInfo(query, offset) ?: throw NoTrackFoundException()
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
     * Gets synced lyrics for an already-resolved [song] (the SongInfo returned by [getSongInfo], carrying the
     * provider-specific lookup token) and returns them formatted as an LRC string. Stateless: the token comes
     * from [song], not from shared fields, so concurrent lookups can't clobber each other.
     * @param song The resolved SongInfo whose provider token (songLink/lrcLibID/neteaseID/qqPayload/appleID) is set.
     * @return The synced lyrics as a string, or null.
     */
    suspend fun getSyncedLyrics(
        song: SongInfo,
        provider: Providers,
        // TODO providers could be a sealed interface to include such parameters
        includeTranslationNetEase: Boolean = false,
        includeRomanizationNetEase: Boolean = false,
        multiPersonWordByWord: Boolean = false,
    ): String? {
        return when (provider) {
            Providers.SPOTIFY -> SpotifyLyricsAPI().getSyncedLyrics(song.songLink ?: "")
            Providers.LRCLIB -> LRCLibAPI().getSyncedLyrics(song.lrcLibID ?: 0)
            Providers.NETEASE -> NeteaseAPI().getSyncedLyrics(
                song.neteaseID ?: 0L, includeTranslationNetEase, includeRomanizationNetEase
            )

            Providers.QQMUSIC -> QQMusicAPI().getSyncedLyrics(song.qqPayload ?: "", multiPersonWordByWord)

            Providers.APPLE -> appleAPI.getSyncedLyrics(
                song.appleID ?: 0L, multiPersonWordByWord
            )
        }
    }
}
