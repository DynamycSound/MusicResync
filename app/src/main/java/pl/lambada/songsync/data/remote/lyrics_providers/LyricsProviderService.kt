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
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult
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
     * Gathers album-cover URLs for a song from every provider that exposes artwork. Candidates are lightly
     * validated against the requested title/artist before being shown, so an unrelated top search result doesn't
     * pollute the picker with random art. We also probe a few more result offsets (especially Spotify) so the
     * picker has more than just one or two options when several editions of the same track exist.
     */
    suspend fun getCoverCandidates(title: String, artist: String, filePath: String? = null, max: Int = 12): List<String> {
        val urls = LinkedHashSet<String>()
        val localViews = buildList {
            add(LocalTrack(title = title, artist = artist))
            FilenameParser.candidates(title, artist, filePath).forEach { c -> add(LocalTrack(c.title, c.artist)) }
        }.distinct()
        val coverQueries = FilenameParser.candidates(title, artist, filePath)
            .map { SongInfo(it.title, it.artist) }
            .distinctBy { "${it.songName}|${it.artistName}" }
            .ifEmpty { listOf(SongInfo(title, artist)) }
            .take(4)

        fun bestScore(remoteTitle: String?, remoteArtist: String?) = localViews
            .map { view -> ConfidenceScorer.score(view, ProviderResult(remoteTitle, remoteArtist)) }
            .maxByOrNull { it.score }

        // Do we actually know the local artist? If a filename gave us no artist we can't demand agreement, so
        // relevance falls back to title only (the old behaviour) instead of rejecting everything.
        val haveLocalArtist = localViews.any { !it.artist.isNullOrBlank() }

        fun looksRelevant(remoteTitle: String?, remoteArtist: String?): Boolean {
            val best = bestScore(remoteTitle, remoteArtist) ?: return false
            // Require BOTH a solid title match and (when we know it) real artist agreement. The old check let a
            // same-title-wrong-artist hit through, so covers for a totally different singer's song showed up.
            val titleOk = best.title >= 0.55
            val artistOk = !haveLocalArtist || best.artist >= 0.70
            return best.score >= ConfidenceScorer.REVIEW_THRESHOLD && titleOk && artistOk
        }

        // Softer art-only relevance for the "I need more than one thumbnail" case: keep covers from obviously
        // the same artist when the title still has *some* overlap, even if that hit wasn't strong enough to be a
        // lyrics match. This broadens the picker with related/album-level art instead of showing zero options.
        fun looksArtistRelated(remoteTitle: String?, remoteArtist: String?): Boolean {
            val best = bestScore(remoteTitle, remoteArtist) ?: return false
            return best.artist >= 0.85 && best.title >= 0.20
        }

        fun looksSameArtist(remoteArtist: String?): Boolean {
            val artistView = localViews.map { it.artist }.firstOrNull { !it.isNullOrBlank() } ?: return false
            val best = ConfidenceScorer.score(LocalTrack(title = null, artist = artistView), ProviderResult(null, remoteArtist))
            return best.artist >= 0.85
        }

        suspend fun collect(provider: Providers, offsets: IntRange) {
            for (query in coverQueries) {
                for (offset in offsets) {
                    if (urls.size >= max) return
                    val info = runCatching { getSongInfo(query, offset, provider) }.getOrNull() ?: break
                    val cover = info.albumCoverLink?.takeIf { it.isNotBlank() } ?: continue
                    if (looksRelevant(info.songName, info.artistName)) urls.add(cover)
                }
            }
        }

        runCatching { refreshSpotifyToken() }
        collect(Providers.APPLE, 0..5)
        collect(Providers.SPOTIFY, 0..5)

        val rescueQueries = coverQueries.map { listOfNotNull(it.artistName?.takeIf { a -> a.isNotBlank() }, it.songName?.takeIf { s -> s.isNotBlank() }).joinToString(" ") }
            .filter { it.isNotBlank() }
            .distinct()

        // Top up with iTunes/Deezer artwork only when their canonicalized title/artist still look relevant.
        if (urls.size < max) {
            for (query in rescueQueries) {
                runCatching { lastResortAPI.canonicalize(query, max) }
                    .getOrDefault(emptyList())
                    .forEach { meta ->
                        val cover = meta.coverUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                        if (urls.size < max && looksRelevant(meta.title, meta.artist)) urls.add(cover)
                    }
                if (urls.size >= max) break
            }
        }

        // If the picker is still too sparse, widen it with artist-related covers from the same canonicalized pool.
        // This intentionally prefers "same artist, somewhat related title" over showing nothing at all.
        if (urls.size < 4) {
            for (query in rescueQueries) {
                runCatching { lastResortAPI.canonicalize(query, max * 2) }
                    .getOrDefault(emptyList())
                    .forEach { meta ->
                        val cover = meta.coverUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                        if (urls.size < max && looksArtistRelated(meta.title, meta.artist)) urls.add(cover)
                    }
                if (urls.size >= 4) break
            }
        }

        // Final art-only fallback: if an exact track simply doesn't have many covers across the providers, fill the
        // picker with same-artist covers rather than returning 0–1 choices. This keeps the picker useful while
        // still avoiding completely unrelated art.
        if (urls.size < 4) {
            val artistQueries = coverQueries.mapNotNull { it.artistName?.takeIf { a -> a.isNotBlank() } }.distinct()
            for (query in artistQueries) {
                runCatching { lastResortAPI.canonicalize(query, max * 3) }
                    .getOrDefault(emptyList())
                    .forEach { meta ->
                        val cover = meta.coverUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                        if (urls.size < max && looksSameArtist(meta.artist)) urls.add(cover)
                    }
                if (urls.size >= 4) break
            }
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
