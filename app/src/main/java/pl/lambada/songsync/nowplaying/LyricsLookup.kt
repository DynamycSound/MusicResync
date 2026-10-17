package pl.lambada.songsync.nowplaying

import pl.lambada.songsync.data.remote.lyrics_providers.MatchConfig
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.matching.TextMatch
import pl.lambada.songsync.util.parseSyncedLrc

/** Lyrics found online: the provider's LRC body, whether it has timing, and which source it came from. */
data class FoundLyrics(val text: String, val synced: Boolean, val provider: Providers)

/**
 * The "Best match" and "one source only" searches behind the source pickers, shared by the live view and the
 * library player so both pick lyrics the same way.
 */
class LyricsLookup(private val matcher: SmartLyricsMatcher) {

    /**
     * The first synced lyrics among [order]'s believable matches, then the last-resort rescue, then plain lyrics.
     * Null when nothing was found.
     */
    suspend fun best(local: LocalTrack, candidates: List<QueryCandidate>, order: List<Providers>): FoundLyrics? {
        // The matcher reads results in this order, so a slow or failing provider first in line holds up everyone
        // behind it until its timeout. LRCLib answers fast with synced lyrics and a duration to check against;
        // asking it first means a sure LRCLib match shows in a second instead of after the slowest provider.
        val config = MatchConfig.fast((listOf(Providers.LRCLIB) + order).distinct())

        val hits = matcher.search(local, candidates, config).filter { it.tier != MatchTier.REJECT }
        for (hit in hits) {
            val text = runCatching { matcher.fetchLyrics(hit, config) }.getOrNull() ?: continue
            if (parseSyncedLrc(text).isNotEmpty()) return FoundLyrics(text, synced = true, hit.provider)
        }

        val rescue = runCatching { matcher.lastResort(local, candidates, config) }.getOrNull()
        if (rescue != null && rescue.synced && parseSyncedLrc(rescue.lyrics).isNotEmpty()) {
            return FoundLyrics(rescue.lyrics, synced = true, Providers.LRCLIB)
        }

        // No synced lyrics anywhere: unsynced lyrics are still worth reading along to.
        val plain = runCatching { matcher.fetchPlainLyrics(local, candidates, config) }.getOrNull()
        val plainText = plain?.plainLyrics ?: rescue?.takeUnless { it.synced }?.lyrics
        return plainText?.takeIf { it.isNotBlank() }?.let { FoundLyrics(it, synced = false, Providers.LRCLIB) }
    }

    /** Lyrics from [provider] only, for when the user picks it: its best synced match, else its plain text. */
    suspend fun on(provider: Providers, local: LocalTrack, candidates: List<QueryCandidate>): FoundLyrics? {
        val config = MatchConfig.fast(listOf(provider))
        var plain: String? = null
        for (hit in matcher.search(local, candidates, config).filter { it.tier != MatchTier.REJECT }) {
            val text = runCatching { matcher.fetchLyrics(hit, config) }.getOrNull() ?: continue
            if (parseSyncedLrc(text).isNotEmpty()) return FoundLyrics(text, synced = true, provider)
            if (plain == null && text.isNotBlank()) plain = text
        }
        return plain?.let { FoundLyrics(it, synced = false, provider) }
    }

    companion object {
        /** A library song as the matcher sees it, read the same way the library search reads it. */
        fun librarySong(title: String, artist: String?, filePath: String?, durationMs: Long?): Pair<LocalTrack, List<QueryCandidate>> {
            val local = LocalTrack(
                title = title,
                // Placeholder artists ("Unknown", …) are noise, not evidence.
                artist = artist?.takeIf { !TextMatch.isJunkArtist(it) },
                durationSec = durationMs?.takeIf { it > 0 }?.let { it / 1000.0 },
            )
            val candidates = FilenameParser.candidates(title, artist, filePath)
                .ifEmpty { listOf(QueryCandidate(title, artist, MatchStrategy.TAGS)) }
            return local to candidates
        }
    }
}
