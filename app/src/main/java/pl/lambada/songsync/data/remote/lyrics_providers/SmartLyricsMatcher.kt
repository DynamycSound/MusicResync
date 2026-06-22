package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import kotlinx.coroutines.delay
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.ConfidenceBreakdown
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.networking.withRetry

private const val TAG = "MusicResync"

/** A scored provider hit: enough to rank it, show it in the manual UI, and later fetch its lyrics. */
data class ScoredHit(
    val provider: Providers,
    val strategy: MatchStrategy,
    val result: ProviderResult,
    val confidence: ConfidenceBreakdown,
    val inlineLyrics: String?,   // LRCLib returns synced lyrics inline with search
    val neteaseId: Long?,        // Netease needs a second fetch by id
) {
    val tier get() = confidence.tier
}

/** Tunables surfaced in Settings ("never run into rate limits"). */
data class MatchConfig(
    val providerOrder: List<Providers> = listOf(Providers.LRCLIB, Providers.NETEASE),
    val maxRetries: Int = 3,
    val requestDelayMs: Long = 200,
    val maxCandidatesPerProvider: Int = 4,
)

/**
 * The brain of MusicResync's batch fetch. For one local track it walks the candidate ladder (best-guess query
 * first) across the configured providers, scores every hit with [ConfidenceScorer], and returns the hits sorted
 * by confidence. The top hit is the auto/verify/manual decision; the rest feed the manual-search suggestions
 * (including "same length as your file" duration matches). Every request is wrapped in [withRetry] with
 * exponential backoff and a polite inter-request delay, and all failures are logged and swallowed so a single
 * bad request can never crash the batch.
 *
 * LRCLib is the fast default (no auth, one request returns metadata + synced lyrics + duration). Netease adds
 * catalogue depth for non-English tracks. Other providers stay available through the manual/single-fetch UI.
 */
class SmartLyricsMatcher(
    private val lrcLib: LRCLibAPI = LRCLibAPI(),
    private val netease: NeteaseAPI = NeteaseAPI(),
    /** Optional: enables Apple/Musixmatch/Spotify/QQ as scored fallbacks in the single-song flow. */
    private val providerService: LyricsProviderService? = null,
) {

    /**
     * @return all scored hits, highest confidence first (may be empty). The first element, if any, is the best
     * match; inspect [ScoredHit.tier] to decide auto-accept / verify / manual.
     */
    suspend fun search(
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig = MatchConfig(),
        log: (String) -> Unit = { Log.i(TAG, it) },
        onAttempt: (provider: Providers) -> Unit = {},
    ): List<ScoredHit> {
        val displayName = listOfNotNull(local.artist, local.title).joinToString(" - ").ifBlank { "<unknown>" }
        log("[match] \"$displayName\"  dur=${local.durationSec?.let { "${it.toInt()}s" } ?: "?"}  candidates=${candidates.size}")

        val hits = LinkedHashMap<String, ScoredHit>() // dedupe by provider+normalized result
        var best = 0.0

        outer@ for (provider in config.providerOrder) {
            onAttempt(provider)
            for (cand in candidates) {
                val query = cand.asSearchString()
                if (query.isBlank()) continue

                val found = when (provider) {
                    Providers.LRCLIB -> searchLrcLib(query, local, cand, config, log)
                    Providers.NETEASE -> searchNetease(query, local, cand, config, log)
                    else -> searchGeneric(provider, local, cand, config, log)
                }

                for (hit in found) {
                    val key = "${hit.provider}|${hit.result.title}|${hit.result.artist}|${hit.result.durationSec}"
                    val existing = hits[key]
                    if (existing == null || hit.confidence.score > existing.confidence.score) hits[key] = hit
                    if (hit.confidence.score > best) {
                        best = hit.confidence.score
                        log("  [${provider.displayName}] ${cand.strategy.label} -> ${hit.result.title} - ${hit.result.artist} (${hit.confidence.percent()}%${if (hit.confidence.durationMatched) ", duration✓" else ""})")
                    }
                }

                delay(config.requestDelayMs)
                if (best >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) {
                    log("  [auto-accept] reached ${(best * 100).toInt()}% — stopping early")
                    break@outer
                }
            }
        }

        val ranked = hits.values.sortedByDescending { it.confidence.score }
        if (ranked.isEmpty()) log("  [no match] nothing found across ${config.providerOrder.joinToString { it.displayName }}")
        return ranked
    }

    /** Resolves the synced LRC body for a chosen hit (inline for LRCLib/generic, second fetch for Netease). */
    suspend fun fetchLyrics(hit: ScoredHit, config: MatchConfig = MatchConfig(), log: (String) -> Unit = { Log.i(TAG, it) }): String? {
        return when (hit.provider) {
            Providers.NETEASE -> hit.neteaseId?.let { id ->
                runCatching { withRetry(config.maxRetries) { netease.getSyncedLyrics(id) } }
                    .onFailure { log("  [Netease] lyric fetch failed: ${it.message}") }
                    .getOrNull()
            }
            else -> hit.inlineLyrics // LRCLib + generic providers carry the lyrics inline
        }?.takeIf { it.isNotBlank() }
    }

    /**
     * Scored single-result path for providers that aren't LRCLib/Netease (Apple, Musixmatch, Spotify, QQ).
     * Each lookup is wrapped so a provider outage (timeout, JSON change) yields no hit instead of an error,
     * and the result is run through the confidence scorer so a wrong "first result" (e.g. a remix) is rejected
     * rather than blindly accepted. Lyrics are only fetched when the match already looks promising.
     */
    private suspend fun searchGeneric(
        provider: Providers, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val service = providerService ?: return emptyList()
        return runCatching {
            val info = withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [${provider.displayName}] retry $a in ${d}ms (${e.message})") }) {
                service.getSongInfo(SongInfo(cand.title, cand.artist), 0, provider)
            } ?: return emptyList()

            val pr = ProviderResult(info.songName, info.artistName, null, null, true)
            val conf = scoreFor(local, pr, cand.strategy)
            // Only spend a lyrics request if the metadata match is at least review-grade.
            val lyrics = if (conf.score >= ConfidenceScorer.REVIEW_THRESHOLD) {
                runCatching {
                    withRetry(config.maxRetries) {
                        service.getSyncedLyrics(
                            info.songName ?: return@withRetry null,
                            info.artistName ?: return@withRetry null,
                            provider,
                        )
                    }
                }.getOrNull()
            } else null

            listOf(ScoredHit(provider, cand.strategy, pr, conf, lyrics, null))
        }.onFailure { log("  [${provider.displayName}] failed: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * Scores a hit, then caps remix/version base-song fallbacks at REVIEW. When the only match we could find is
     * the BASE song for a remix (via the loosened candidate), its lyrics are usually right but the *timing*
     * differs (a remix changes tempo), so we never silently auto-accept it -- the user verifies and nudges the
     * offset in the player instead.
     */
    private fun scoreFor(local: LocalTrack, pr: ProviderResult, strategy: MatchStrategy): ConfidenceBreakdown {
        val conf = ConfidenceScorer.score(local, pr)
        return if (strategy == MatchStrategy.FILENAME_LOOSE && conf.tier == MatchTier.AUTO_ACCEPT)
            conf.copy(score = 0.80, tier = MatchTier.REVIEW)
        else conf
    }

    private suspend fun searchLrcLib(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val results = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [LRCLib] retry $a in ${d}ms (${e.message})") }) {
                lrcLib.searchCandidates(query)
            }
        }.onFailure { log("  [LRCLib] search failed: ${it.message}") }.getOrDefault(emptyList())

        // Score ALL synced hits, then keep the best few. A title-only query can return 20 results where the
        // right track (matched by duration) is well down the relevance list -- scoring before the cap lets it
        // survive instead of being truncated away by LRCLib's ordering.
        return results
            .filter { !it.syncedLyrics.isNullOrBlank() } // batch needs synced lyrics
            .map { r ->
                val pr = ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, true)
                ScoredHit(Providers.LRCLIB, cand.strategy, pr, scoreFor(local, pr, cand.strategy), r.syncedLyrics, null)
            }
            .sortedByDescending { it.confidence.score }
            .take(config.maxCandidatesPerProvider)
    }

    private suspend fun searchNetease(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val songs = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [Netease] retry $a in ${d}ms (${e.message})") }) {
                netease.searchCandidates(query, config.maxCandidatesPerProvider)
            }
        }.onFailure { log("  [Netease] search failed: ${it.message}") }.getOrDefault(emptyList())

        return songs.map { s ->
            val pr = ProviderResult(
                title = s.name,
                artist = s.artists.joinToString(", ") { it.name },
                durationSec = s.duration?.let { it / 1000.0 },
                album = s.album?.name,
                hasSyncedLyrics = true,
            )
            ScoredHit(Providers.NETEASE, cand.strategy, pr, scoreFor(local, pr, cand.strategy), null, s.id)
        }
    }
}
